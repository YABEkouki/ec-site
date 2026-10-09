package com.example.ecsite.service.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditMonitoringSummary;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditWarning;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.AdminPaymentDiscrepancyAuditRunService;

/**
 * Locks warning states (alphabetical type order), then existing notifications (ID order).
 * Delivery preflight must lock states first, then the notification, before moving CLAIMED to SENDING.
 * It must exclude INCLUDED items whose state is inactive or whose episode differs, and cancel if none remain.
 * Do not invoke observation after acquiring only a notification lock: that reverses the shared lock order.
 * Already-started SENDING is never cancelled here; returned RETRY_WAIT records are checked on the next observation.
 * SMTP and claim/lease transitions are deliberately outside this component.
 */
@Service
public class PaymentAuditNotificationObservationTransaction {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private final PaymentDiscrepancyAuditNotificationProperties properties;
    private final AdminPaymentDiscrepancyAuditRunService monitoring;
    private final PaymentAuditNotificationStateRepository states;
    private final PaymentAuditNotificationRepository notifications;
    private final PaymentAuditNotificationItemRepository items;
    private final String fromAddress;
    private final String adminUrl;

    public PaymentAuditNotificationObservationTransaction(PaymentDiscrepancyAuditNotificationProperties properties,
            AdminPaymentDiscrepancyAuditRunService monitoring, PaymentAuditNotificationStateRepository states,
            PaymentAuditNotificationRepository notifications, PaymentAuditNotificationItemRepository items,
            @Value("${app.mail.from}") String fromAddress, @Value("${app.base-url}") String baseUrl) {
        this.properties = properties;
        this.monitoring = monitoring;
        this.states = states;
        this.notifications = notifications;
        this.items = items;
        this.fromAddress = fromAddress;
        this.adminUrl = baseUrl.replaceAll("/+$", "") + "/admin/payment-discrepancy-audits";
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public void observe() {
        if (!properties.enabled()) return;
        var current = new EnumMap<PaymentAuditNotificationWarningType, PaymentAuditNotificationState>(PaymentAuditNotificationWarningType.class);
        for (var state : states.findAllForObservation()) current.put(state.getWarningType(), state);
        if (current.size() != PaymentAuditNotificationWarningType.values().length)
            throw new IllegalStateException("Six seeded payment audit warning states are required");
        // Reuse Feature114 verbatim; the read-only service joins this writable snapshot transaction.
        var summary = monitoring.monitoringSummary();
        Instant now = instant(summary.evaluatedAt());
        var observed = new EnumMap<PaymentAuditNotificationWarningType, AdminPaymentDiscrepancyAuditWarning>(PaymentAuditNotificationWarningType.class);
        for (var warning : summary.warnings()) observed.put(type(warning), warning);
        for (var state : current.values()) {
            boolean present = observed.containsKey(state.getWarningType());
            boolean held = state.isActive() && state.getWarningType() == PaymentAuditNotificationWarningType.LATEST_FAILURE
                && !present && !latestSucceeded(summary);
            state.setLastEvaluatedAt(now);
            if (present || held) {
                if (!state.isActive()) {
                    state.setEpisodeNo(Math.incrementExact(state.getEpisodeNo()));
                    state.setFirstObservedAt(now);
                    state.setResolvedAt(null);
                    state.setActive(true);
                }
                state.setLastObservedAt(now);
            } else if (state.isActive()) {
                state.setActive(false);
                state.setResolvedAt(now);
            }
        }
        cancelResolved(current, now);
        var eligible = new ArrayList<AdminPaymentDiscrepancyAuditWarning>();
        // Preserve the actual Feature114 warning order, rather than the database lock order.
        for (var warning : summary.warnings()) {
            var state = current.get(type(warning));
            if (state.getWarningType() == PaymentAuditNotificationWarningType.NO_HISTORY
                    && now.isBefore(state.getFirstObservedAt().plus(properties.noHistoryGracePeriod()))) continue;
            if (items.findByStateWarningTypeAndEpisodeNo(state.getWarningType(), state.getEpisodeNo()).isEmpty())
                eligible.add(warning);
        }
        if (!eligible.isEmpty()) createNotification(eligible, current, now);
        // Flush within the proxy transaction; commit failures are also caught by the outer retry boundary.
        states.flush();
        notifications.flush();
        items.flush();
    }

    private static boolean latestSucceeded(AdminPaymentDiscrepancyAuditMonitoringSummary summary) {
        return summary.latestRun() != null && summary.latestRun().status() == PaymentDiscrepancyAuditRunStatus.SUCCESS;
    }

    private void cancelResolved(EnumMap<PaymentAuditNotificationWarningType, PaymentAuditNotificationState> current, Instant now) {
        for (var notification : notifications.findAllCancelableForObservation()) {
            var notificationItems = items.findByNotificationIdOrderByIdAsc(notification.getId());
            for (var item : notificationItems) {
                if (item.getItemStatus() != PaymentAuditNotificationItemStatus.INCLUDED) continue;
                var state = current.get(item.getState().getWarningType());
                if (!state.isActive() || state.getEpisodeNo() != item.getEpisodeNo()) {
                    item.setItemStatus(PaymentAuditNotificationItemStatus.REMOVED_RESOLVED);
                    // Old episodes can return from delivery later; their absence is observed now.
                    item.setResolvedAt(state.getEpisodeNo() == item.getEpisodeNo() ? state.getResolvedAt() : now);
                    item.setRemovedAt(now);
                    notification.setUpdatedAt(now);
                }
            }
            if (!notificationItems.isEmpty() && notificationItems.stream()
                    .allMatch(item -> item.getItemStatus() == PaymentAuditNotificationItemStatus.REMOVED_RESOLVED)) {
                notification.setStatus(PaymentAuditNotificationStatus.CANCELLED);
                notification.setCloseReason(PaymentAuditNotificationCloseReason.RESOLVED);
                notification.setNextAttemptAt(null);
                notification.setClosedAt(now);
                notification.setUpdatedAt(now);
            }
        }
    }

    private void createNotification(List<AdminPaymentDiscrepancyAuditWarning> warnings,
            EnumMap<PaymentAuditNotificationWarningType, PaymentAuditNotificationState> current, Instant now) {
        var notification = new PaymentAuditNotification();
        notification.setStatus(PaymentAuditNotificationStatus.PENDING);
        notification.setEvaluatedAt(now);
        notification.setFromAddress(fromAddress);
        notification.setRecipients(properties.recipients().toArray(String[]::new));
        notification.setRecipientSetHash(recipientHash(properties.recipients()));
        notification.setAdminUrl(adminUrl);
        notification.setMaxAttempts(properties.maxAttempts());
        notification.setRetryDelayMs(properties.retryDelay().toMillis());
        notification.setNextAttemptAt(now);
        notification.setCreatedAt(now);
        notification.setUpdatedAt(now);
        notifications.save(notification);
        for (var warning : warnings) {
            var state = current.get(type(warning));
            var item = new PaymentAuditNotificationItem();
            item.setNotification(notification);
            item.setState(state);
            item.setEpisodeNo(state.getEpisodeNo());
            item.setItemStatus(PaymentAuditNotificationItemStatus.INCLUDED);
            item.setFirstObservedAt(state.getFirstObservedAt());
            item.setRelatedAt(warning.relatedAt() == null ? null : instant(warning.relatedAt()));
            item.setWarningCount(warning.count());
            item.setErrorCode(warning.errorCode());
            item.setCreatedAt(now);
            items.save(item);
        }
    }

    private static PaymentAuditNotificationWarningType type(AdminPaymentDiscrepancyAuditWarning warning) {
        return PaymentAuditNotificationWarningType.valueOf(warning.type().name());
    }

    private static Instant instant(LocalDateTime time) {
        return time.atZone(TOKYO).toInstant();
    }

    private static String recipientHash(List<String> recipients) {
        try {
            String canonical = String.join("\n", recipients.stream().sorted().toList());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
