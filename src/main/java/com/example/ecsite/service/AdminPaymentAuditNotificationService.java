package com.example.ecsite.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.dto.*;
import com.example.ecsite.exception.PaymentAuditNotificationNotFoundException;
import com.example.ecsite.form.AdminPaymentAuditNotificationSearchForm;
import com.example.ecsite.repository.*;
import com.example.ecsite.repository.projection.AdminPaymentAuditNotificationItemProjection;

/** Admin history reader. No dependency on notification settings, observation, delivery or SMTP. */
@Service
@Transactional(readOnly = true)
public class AdminPaymentAuditNotificationService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private final PaymentAuditNotificationRepository notifications;
    private final PaymentAuditNotificationItemRepository items;
    private final PaymentAuditNotificationAttemptRepository attempts;
    private final Validator validator;

    public AdminPaymentAuditNotificationService(PaymentAuditNotificationRepository notifications,
            PaymentAuditNotificationItemRepository items, PaymentAuditNotificationAttemptRepository attempts,
            Validator validator) {
        this.notifications = notifications; this.items = items; this.attempts = attempts; this.validator = validator;
    }

    public Page<AdminPaymentAuditNotificationListItem> search(AdminPaymentAuditNotificationSearchForm form,
            int page, int size) {
        if (form == null) throw new IllegalArgumentException("検索条件が必要です。");
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        Instant from = form.getFrom() == null ? null : form.getFrom().atStartOfDay(TOKYO).toInstant();
        Instant to = form.getTo() == null ? null : form.getTo().plusDays(1).atStartOfDay(TOKYO).toInstant();
        int safeSize = size >= 100 ? 100 : size == 50 ? 50 : 20;
        int safePage = Math.max(page, 0);
        boolean exceedsOffset = (long) safePage * safeSize > Integer.MAX_VALUE;
        var result = notifications.searchForAdmin(form.getNotificationId(), form.getStatus(), form.getWarningType(),
            from, to, PageRequest.of(exceedsOffset ? 0 : safePage, safeSize));
        int lastPage = Math.max(0, result.getTotalPages() - 1);
        if (exceedsOffset && lastPage > 0 || result.getNumber() > lastPage) {
            result = notifications.searchForAdmin(form.getNotificationId(), form.getStatus(), form.getWarningType(),
                from, to, PageRequest.of(lastPage, safeSize));
        }
        List<Long> ids = result.getContent().stream().map(n -> n.getId()).toList();
        Map<Long, List<AdminPaymentAuditNotificationItemProjection>> grouped = ids.isEmpty() ? Map.of()
            : items.findAllForAdminByNotificationIds(ids).stream()
                .collect(Collectors.groupingBy(AdminPaymentAuditNotificationItemProjection::getNotificationId));
        return result.map(n -> {
            var warnings = grouped.getOrDefault(n.getId(), List.of());
            return new AdminPaymentAuditNotificationListItem(n.getId(), tokyo(n.getCreatedAt()), n.getStatus(),
                warnings.stream().map(AdminPaymentAuditNotificationItemProjection::getWarningType).distinct().sorted().toList(),
                warnings.size(), n.getAttemptCount(), n.getMaxAttempts(), tokyo(n.getSentAt()),
                tokyo(n.getNextAttemptAt()), n.getDeliveryUncertain());
        });
    }

    /** One short snapshot across header/items/attempts, without locking delivery rows. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminPaymentAuditNotificationDetail findById(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("通知IDは正の整数で指定してください。");
        var n = notifications.findSummaryForAdmin(id).orElseThrow(() -> new PaymentAuditNotificationNotFoundException(id));
        var warnings = items.findAllForAdminByNotificationIds(List.of(id)).stream().map(i ->
            new AdminPaymentAuditNotificationWarningItem(i.getWarningType(), i.getEpisodeNo(), i.getItemStatus(),
                tokyo(i.getFirstObservedAt()), tokyo(i.getRelatedAt()), i.getWarningCount(), i.getErrorCode(),
                tokyo(i.getResolvedAt()), tokyo(i.getRemovedAt()))).toList();
        var history = attempts.findForAdminByNotificationId(id).stream().map(a ->
            new AdminPaymentAuditNotificationAttemptItem(a.getAttemptNo(), a.getResult(), tokyo(a.getStartedAt()),
                tokyo(a.getFinishedAt()), a.getFailureCode())).toList();
        return new AdminPaymentAuditNotificationDetail(n.getId(), n.getStatus(), tokyo(n.getCreatedAt()),
            tokyo(n.getUpdatedAt()), tokyo(n.getEvaluatedAt()), n.getAttemptCount(), n.getMaxAttempts(),
            tokyo(n.getSentAt()), tokyo(n.getNextAttemptAt()), tokyo(n.getClosedAt()), n.getCloseReason(),
            n.getDeliveryUncertain(), n.getRecipientCount(), warnings, history);
    }

    private static LocalDateTime tokyo(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, TOKYO);
    }
}
