package com.example.ecsite.repository;

import com.example.ecsite.entity.PaymentAuditNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.example.ecsite.entity.PaymentAuditNotificationStatus;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;
import com.example.ecsite.repository.projection.AdminPaymentAuditNotificationProjection;
import java.util.Optional;

public interface PaymentAuditNotificationRepository extends JpaRepository<PaymentAuditNotification, Long> {
    /** Lock after warning states, in ascending notification ID order. No claim mutation. */
    @Query(value = """
        select * from payment_audit_notifications
        where status in ('PENDING', 'RETRY_WAIT') order by id for update
        """, nativeQuery = true)
    List<PaymentAuditNotification> findAllCancelableForObservation();
    @Query(value = """
        select * from payment_audit_notifications
        where status in ('PENDING', 'RETRY_WAIT') and next_attempt_at <= clock_timestamp()
        order by id limit 1 for update skip locked
        """, nativeQuery = true)
    Optional<PaymentAuditNotification> findNextForClaim();

    @Query(value = """
        select * from payment_audit_notifications
        where status in ('CLAIMED', 'SENDING') and lease_until <= clock_timestamp()
        order by id limit 20 for update skip locked
        """, nativeQuery = true)
    List<PaymentAuditNotification> findExpiredForRecovery();

    @Query(value = "select * from payment_audit_notifications where id = :id for update", nativeQuery = true)
    Optional<PaymentAuditNotification> findForDelivery(long id);

    /** Lock-free, scalar history search. EXISTS preserves notification-level paging and totals. */
    @Query(value = """
        select n.id as id, n.status as status, n.createdAt as createdAt, n.updatedAt as updatedAt,
            n.evaluatedAt as evaluatedAt, n.attemptCount as attemptCount, n.maxAttempts as maxAttempts,
            n.sentAt as sentAt, n.nextAttemptAt as nextAttemptAt, n.closedAt as closedAt,
            n.closeReason as closeReason, n.deliveryUncertain as deliveryUncertain,
            function('cardinality', n.recipients) as recipientCount
        from PaymentAuditNotification n
        where (:notificationId is null or n.id = :notificationId)
          and (:status is null or n.status = :status)
          and (cast(:from as timestamp) is null or n.createdAt >= :from)
          and (cast(:toExclusive as timestamp) is null or n.createdAt < :toExclusive)
          and (:warningType is null or exists (
              select i.id from PaymentAuditNotificationItem i
              where i.notification = n and i.state.warningType = :warningType))
        order by n.createdAt desc, n.id desc
        """, countQuery = """
        select count(n) from PaymentAuditNotification n
        where (:notificationId is null or n.id = :notificationId)
          and (:status is null or n.status = :status)
          and (cast(:from as timestamp) is null or n.createdAt >= :from)
          and (cast(:toExclusive as timestamp) is null or n.createdAt < :toExclusive)
          and (:warningType is null or exists (
              select i.id from PaymentAuditNotificationItem i
              where i.notification = n and i.state.warningType = :warningType))
        """)
    Page<AdminPaymentAuditNotificationProjection> searchForAdmin(Long notificationId,
        PaymentAuditNotificationStatus status, PaymentAuditNotificationWarningType warningType,
        Instant from, Instant toExclusive, Pageable pageable);

    @Query("""
        select n.id as id, n.status as status, n.createdAt as createdAt, n.updatedAt as updatedAt,
            n.evaluatedAt as evaluatedAt, n.attemptCount as attemptCount, n.maxAttempts as maxAttempts,
            n.sentAt as sentAt, n.nextAttemptAt as nextAttemptAt, n.closedAt as closedAt,
            n.closeReason as closeReason, n.deliveryUncertain as deliveryUncertain,
            function('cardinality', n.recipients) as recipientCount
        from PaymentAuditNotification n
        where n.id = :id
        """)
    Optional<AdminPaymentAuditNotificationProjection> findSummaryForAdmin(Long id);
}
