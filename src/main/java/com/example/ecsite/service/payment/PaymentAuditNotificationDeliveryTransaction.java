package com.example.ecsite.service.payment;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditWarning;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;

/** Short independent transactions only; never calls SMTP or waits on the SMTP executor. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class PaymentAuditNotificationDeliveryTransaction {
    private static final int MAX_DELIVERY_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(PaymentAuditNotificationDeliveryTransaction.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        .withZone(ZoneId.of("Asia/Tokyo"));
    private final PaymentAuditNotificationRepository notifications;
    private final PaymentAuditNotificationStateRepository states;
    private final PaymentAuditNotificationItemRepository items;
    private final PaymentAuditNotificationAttemptRepository attempts;
    private final PaymentDiscrepancyAuditNotificationProperties properties;
    private final JdbcTemplate jdbc;

    public record Claim(long id, UUID token) {}

    public PaymentAuditNotificationDeliveryTransaction(PaymentAuditNotificationRepository notifications,
            PaymentAuditNotificationStateRepository states, PaymentAuditNotificationItemRepository items,
            PaymentAuditNotificationAttemptRepository attempts, PaymentDiscrepancyAuditNotificationProperties properties,
            JdbcTemplate jdbc) {
        this.notifications = notifications; this.states = states; this.items = items;
        this.attempts = attempts; this.properties = properties; this.jdbc = jdbc;
    }

    public Optional<Claim> claim(UUID owner) {
        if (!properties.enabled()) return Optional.empty();
        return notifications.findNextForClaim().map(n -> {
            Instant now = databaseNow();
            // Stage1 accepts larger persistence budgets; stage3 delivery is bounded to three attempts.
            if (n.getAttemptCount() == 0) n.setMaxAttempts(Math.min(n.getMaxAttempts(), MAX_DELIVERY_ATTEMPTS));
            UUID token = UUID.randomUUID();
            n.setStatus(PaymentAuditNotificationStatus.CLAIMED);
            n.setNextAttemptAt(null); n.setClaimToken(token); n.setClaimedBy(owner);
            n.setLeaseUntil(now.plus(properties.claimLease())); n.setUpdatedAt(now);
            return new Claim(n.getId(), token);
        });
    }

    /** Lock warning states first (alphabetical), then this single notification (ID order). */
    public Optional<PaymentAuditNotificationMail> prepare(Claim claim) {
        if (!properties.enabled()) return Optional.empty();
        var current = new EnumMap<PaymentAuditNotificationWarningType, PaymentAuditNotificationState>(PaymentAuditNotificationWarningType.class);
        for (var state : states.findAllForObservation()) current.put(state.getWarningType(), state);
        if (current.size() != PaymentAuditNotificationWarningType.values().length)
            throw new IllegalStateException("Six seeded payment audit warning states are required");
        var found = notifications.findForDelivery(claim.id());
        Instant now = databaseNow();
        if (found.isEmpty() || !owned(found.get(), claim, PaymentAuditNotificationStatus.CLAIMED, now)) return Optional.empty();
        var n = found.get();
        var remaining = new ArrayList<PaymentAuditNotificationItem>();
        for (var item : items.findByNotificationIdOrderByIdAsc(n.getId())) {
            if (item.getItemStatus() != PaymentAuditNotificationItemStatus.INCLUDED) continue;
            var state = current.get(item.getState().getWarningType());
            if (!state.isActive() || state.getEpisodeNo() != item.getEpisodeNo()) {
                item.setItemStatus(PaymentAuditNotificationItemStatus.REMOVED_RESOLVED);
                item.setResolvedAt(state.getEpisodeNo() == item.getEpisodeNo() ? state.getResolvedAt() : now);
                item.setRemovedAt(now);
            } else remaining.add(item);
        }
        if (remaining.isEmpty()) { cancel(n, now, PaymentAuditNotificationCloseReason.RESOLVED); return Optional.empty(); }
        // Same normalization as Properties: local part preserved, domain lowercased, trimmed.
        var effective = Arrays.stream(n.getRecipients()).map(PaymentAuditNotificationDeliveryTransaction::normalize)
            .filter(properties.recipients()::contains).distinct().toList();
        if (effective.isEmpty()) {
            cancel(n, now, PaymentAuditNotificationCloseReason.NO_VALID_RECIPIENTS);
            return Optional.empty();
        }
        remaining.sort(Comparator.comparing(i -> i.getState().getWarningType()));
        var mail = render(n, remaining, effective);
        // Reservation and message history commit before the executor is allowed to invoke SMTP.
        n.setAttemptCount(Math.incrementExact(n.getAttemptCount()));
        n.setStatus(PaymentAuditNotificationStatus.SENDING);
        n.setLeaseUntil(now.plus(properties.claimLease())); n.setUpdatedAt(now);
        var attempt = new PaymentAuditNotificationAttempt();
        attempt.setNotification(n); attempt.setAttemptNo(n.getAttemptCount()); attempt.setClaimToken(claim.token());
        attempt.setResult(PaymentAuditNotificationAttemptResult.IN_PROGRESS); attempt.setStartedAt(now);
        attempt.setSubject(mail.subject()); attempt.setBody(mail.body()); attempt.setRecipients(effective.toArray(String[]::new));
        attempts.save(attempt);
        return Optional.of(mail);
    }

    public boolean complete(Claim claim, PaymentAuditNotificationAttemptResult result, PaymentAuditNotificationFailureCode code) {
        if (result == null || result == PaymentAuditNotificationAttemptResult.IN_PROGRESS
                || (result == PaymentAuditNotificationAttemptResult.SUCCESS) != (code == null))
            throw new IllegalArgumentException("A final result and matching safe failure code are required");
        var found = notifications.findForDelivery(claim.id());
        Instant now = databaseNow();
        if (found.isEmpty() || !owned(found.get(), claim, PaymentAuditNotificationStatus.SENDING, now)) return false;
        finish(found.get(), claim.token(), result, code, now);
        return true;
    }

    public void recoverExpired() {
        if (!properties.enabled()) return;
        for (var n : notifications.findExpiredForRecovery()) {
            Instant now = databaseNow();
            if (n.getStatus() == PaymentAuditNotificationStatus.SENDING) {
                finish(n, n.getClaimToken(), PaymentAuditNotificationAttemptResult.UNKNOWN,
                    PaymentAuditNotificationFailureCode.CLAIM_LOST, now);
            } else {
                n.setStatus(n.getAttemptCount() == 0 ? PaymentAuditNotificationStatus.PENDING : PaymentAuditNotificationStatus.RETRY_WAIT);
                clearClaim(n); n.setNextAttemptAt(now); n.setUpdatedAt(now);
            }
        }
    }

    private void finish(PaymentAuditNotification n, UUID token, PaymentAuditNotificationAttemptResult result,
            PaymentAuditNotificationFailureCode code, Instant now) {
        var attempt = attempts.findByNotificationIdAndClaimToken(n.getId(), token)
            .orElseThrow(() -> new IllegalStateException("Reserved notification attempt is required"));
        if (attempt.getResult() != PaymentAuditNotificationAttemptResult.IN_PROGRESS)
            throw new IllegalStateException("Notification attempt is already final");
        attempt.setResult(result); attempt.setFailureCode(code); attempt.setFinishedAt(now);
        clearClaim(n); n.setUpdatedAt(now);
        if (result == PaymentAuditNotificationAttemptResult.SUCCESS) {
            n.setStatus(PaymentAuditNotificationStatus.SENT); n.setSentAt(now); n.setClosedAt(now);
        } else {
            if (result == PaymentAuditNotificationAttemptResult.UNKNOWN) n.setDeliveryUncertain(true);
            if (n.getAttemptCount() >= n.getMaxAttempts()) {
                n.setStatus(PaymentAuditNotificationStatus.EXHAUSTED); n.setClosedAt(now);
                n.setCloseReason(PaymentAuditNotificationCloseReason.MAX_ATTEMPTS);
                var types = items.findByNotificationIdOrderByIdAsc(n.getId()).stream()
                    .map(i -> i.getState().getWarningType().name()).toList();
                long id = n.getId();
                int count = n.getAttemptCount();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        log.atError().addKeyValue("notificationId", id).addKeyValue("warningTypes", types)
                            .addKeyValue("attemptCount", count).addKeyValue("failureCode", code)
                            .log("Payment audit notification exhausted");
                    }
                });
            } else {
                n.setStatus(PaymentAuditNotificationStatus.RETRY_WAIT);
                n.setNextAttemptAt(now.plusMillis(n.getRetryDelayMs()));
            }
        }
    }

    private static boolean owned(PaymentAuditNotification n, Claim claim, PaymentAuditNotificationStatus status, Instant now) {
        return n.getStatus() == status && claim.token().equals(n.getClaimToken()) && n.getLeaseUntil().isAfter(now);
    }
    private Instant databaseNow() { return jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant(); }
    private static void clearClaim(PaymentAuditNotification n) { n.setClaimToken(null); n.setClaimedBy(null); n.setLeaseUntil(null); }
    private static void cancel(PaymentAuditNotification n, Instant now, PaymentAuditNotificationCloseReason reason) {
        n.setStatus(PaymentAuditNotificationStatus.CANCELLED); n.setCloseReason(reason); n.setClosedAt(now);
        n.setUpdatedAt(now); n.setNextAttemptAt(null); clearClaim(n);
    }
    private static String normalize(String address) {
        String value = address.trim(); int at = value.lastIndexOf('@');
        return value.substring(0, at) + value.substring(at).toLowerCase(Locale.ROOT);
    }
    private static PaymentAuditNotificationMail render(PaymentAuditNotification n,
            List<PaymentAuditNotificationItem> remaining, List<String> recipients) {
        var body = new StringBuilder("PAY.JP決済監査で警告を検出しました。\n\n通知ID：").append(n.getId())
            .append("\n判定日時：").append(TIME.format(n.getEvaluatedAt())).append("\n");
        for (var item : remaining) {
            var type = item.getState().getWarningType();
            body.append("\n警告種別：").append(AdminPaymentDiscrepancyAuditWarning.Type.valueOf(type.name()).getTitle())
                .append("（").append(type.name()).append("）\n初回観測日時：").append(TIME.format(item.getFirstObservedAt())).append("\n");
            if (item.getRelatedAt() != null) body.append("関連日時：").append(TIME.format(item.getRelatedAt())).append("\n");
            if (item.getWarningCount() != null) body.append("件数：").append(item.getWarningCount()).append("\n");
            if (item.getErrorCode() != null) body.append("エラーコード：").append(item.getErrorCode().name()).append("\n");
        }
        body.append("\n管理者監視画面：\n").append(n.getAdminUrl()).append("\n\n管理画面にログインして状況を確認してください。\n");
        return new PaymentAuditNotificationMail(n.getFromAddress(), recipients,
            "【ECサイト】PAY.JP決済監査の警告（" + remaining.size() + "種類）", body.toString());
    }
}
