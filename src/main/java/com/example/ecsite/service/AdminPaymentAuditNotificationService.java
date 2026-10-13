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
    private final PaymentAuditNotificationHandlingRepository handlings;
    private final PaymentAuditNotificationHandlingHistoryRepository handlingHistories;
    private final PaymentAuditNotificationHandlingHistoryUnknownRepository handlingUnknowns;
    private final PaymentAuditNotificationHandlingToken tokens;

    public AdminPaymentAuditNotificationService(PaymentAuditNotificationRepository notifications,
            PaymentAuditNotificationItemRepository items, PaymentAuditNotificationAttemptRepository attempts,
            Validator validator) {
        this(notifications,items,attempts,validator,null,null,null,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminPaymentAuditNotificationService(PaymentAuditNotificationRepository notifications,
        PaymentAuditNotificationItemRepository items, PaymentAuditNotificationAttemptRepository attempts, Validator validator,
        PaymentAuditNotificationHandlingRepository handlings, PaymentAuditNotificationHandlingHistoryRepository histories,
        PaymentAuditNotificationHandlingHistoryUnknownRepository unknowns, @org.springframework.lang.Nullable PaymentAuditNotificationHandlingToken tokens) {
        this.notifications=notifications; this.items=items; this.attempts=attempts; this.validator=validator;
        this.handlings=handlings; this.handlingHistories=histories; this.handlingUnknowns=unknowns; this.tokens=tokens;
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
            from, to, form.getHandlingStatus(), form.isActionableOnly(), form.isRequiresRecheck(), PageRequest.of(exceedsOffset ? 0 : safePage, safeSize));
        int lastPage = Math.max(0, result.getTotalPages() - 1);
        if (exceedsOffset && lastPage > 0 || result.getNumber() > lastPage) {
            result = notifications.searchForAdmin(form.getNotificationId(), form.getStatus(), form.getWarningType(),
                from, to, form.getHandlingStatus(), form.isActionableOnly(), form.isRequiresRecheck(), PageRequest.of(lastPage, safeSize));
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
                tokyo(n.getNextAttemptAt()), n.getDeliveryUncertain(),
                n.getStatus() == com.example.ecsite.entity.PaymentAuditNotificationStatus.EXHAUSTED || n.getDeliveryUncertain(),
                n.getHandlingStatus() == null ? com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.UNREVIEWED : n.getHandlingStatus(),
                Boolean.TRUE.equals(n.getRequiresRecheck()), tokyo(n.getHandlingUpdatedAt()));
        });
    }

    /** One short snapshot across header/items/attempts, without locking delivery rows. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminPaymentAuditNotificationDetail findById(Long id) {
        return detail(id,null);
    }

    /** Caller must supply the administrator ID from the authenticated principal, never a request parameter. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminPaymentAuditNotificationDetail findById(Long id, Long administratorId) {
        if(administratorId == null || administratorId <= 0) throw new IllegalArgumentException("管理者IDが不正です。");
        return detail(id,administratorId);
    }

    private AdminPaymentAuditNotificationDetail detail(Long id, Long administratorId) {
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
            n.getDeliveryUncertain(), n.getRecipientCount(), warnings, history,
            handlingDetail(id,administratorId,n.getStatus(),n.getDeliveryUncertain(),history));
    }

    private AdminPaymentAuditNotificationHandlingDetail handlingDetail(Long id, Long administratorId,
        com.example.ecsite.entity.PaymentAuditNotificationStatus deliveryStatus, boolean uncertain,
        List<AdminPaymentAuditNotificationAttemptItem> attempts) {
        boolean actionable=deliveryStatus == com.example.ecsite.entity.PaymentAuditNotificationStatus.EXHAUSTED || uncertain;
        var current=attempts.stream().filter(a->a.result()==com.example.ecsite.entity.PaymentAuditNotificationAttemptResult.UNKNOWN)
            .map(AdminPaymentAuditNotificationAttemptItem::attemptNo).distinct().sorted().toList();
        var h=handlings==null ? java.util.Optional.<com.example.ecsite.entity.PaymentAuditNotificationHandling>empty() : handlings.findById(id);
        var status=h.map(com.example.ecsite.entity.PaymentAuditNotificationHandling::getHandlingStatus)
            .orElse(com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.UNREVIEWED);
        var histories=handlingHistories==null ? List.<com.example.ecsite.entity.PaymentAuditNotificationHandlingHistory>of()
            : handlingHistories.findByNotificationIdOrderByChangedAtAscIdAsc(id);
        var rows=histories.stream().map(x->new AdminPaymentAuditNotificationHandlingHistoryItem(x.getId(),x.getFromStatus(),x.getToStatus(),
            tokyo(x.getChangedAt()),x.getActorUserId(),x.getActorUsername(),x.getComment())).toList();
        var last=histories.isEmpty()?null:histories.getLast();
        var resolvedId=h.map(com.example.ecsite.entity.PaymentAuditNotificationHandling::getLastResolvedHistoryId).orElse(null);
        List<Integer> confirmed=resolvedId==null || handlingUnknowns==null ? List.of()
            : handlingUnknowns.findByHistoryIdOrderByAttemptNoAsc(resolvedId).stream()
                .map(com.example.ecsite.entity.PaymentAuditNotificationHandlingHistoryUnknown::getAttemptNo).toList();
        boolean recheck=status==com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.RESOLVED
            && current.stream().anyMatch(number->!confirmed.contains(number));
        boolean available=actionable && administratorId!=null && tokens!=null && tokens.isConfigured();
        String token=available?tokens.issue(id,administratorId,h.isPresent(),h.map(com.example.ecsite.entity.PaymentAuditNotificationHandling::getVersion).orElse(0L),current):null;
        return new AdminPaymentAuditNotificationHandlingDetail(actionable,status,recheck,
            h.map(x->tokyo(x.getUpdatedAt())).orElse(null),last==null?null:last.getActorUserId(),last==null?null:last.getActorUsername(),
            rows,confirmed,current,token,available);
    }

    private static LocalDateTime tokyo(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, TOKYO);
    }
}
