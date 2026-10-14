package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.example.ecsite.entity.PaymentAuditNotification;
import com.example.ecsite.entity.PaymentAuditNotificationManualResend;

public interface PaymentAuditNotificationManualResendRepository extends JpaRepository<PaymentAuditNotificationManualResend, Long> {
    List<PaymentAuditNotificationManualResend> findByNotificationIdOrderByRequestNoAsc(Long notificationId);
    Optional<PaymentAuditNotificationManualResend> findByRequestNonce(UUID requestNonce);

    /** Acquire before manual request/attempt locks. Never update the original notification. */
    @Query(value = "select * from payment_audit_notifications where id=:id for update", nativeQuery = true)
    Optional<PaymentAuditNotification> lockNotification(long id);

    /** Call only after locking the original notification (and states if needed). */
    @Query(value = "select * from payment_audit_notification_manual_resends where id=:id for update", nativeQuery = true)
    Optional<PaymentAuditNotificationManualResend> findForUpdate(long id);

    /** Claim-only transaction: commit before taking state or original-notification locks. */
    @Query(value = """
        select * from payment_audit_notification_manual_resends where status='PENDING'
        order by requested_at, id limit 1 for update skip locked
        """, nativeQuery = true)
    Optional<PaymentAuditNotificationManualResend> findNextForClaim();

    /** Lock-free candidates. Recovery must then lock original -> request and recheck lease/status. */
    @Query(value = """
        select id from payment_audit_notification_manual_resends
        where status in ('CLAIMED','SENDING') and lease_until <= clock_timestamp()
        order by notification_id, id limit 20
        """, nativeQuery = true)
    List<Long> findExpiredIdsForRecovery();
    /** Scalar lookup avoids caching a request before the parent lock during recovery. */
    @Query(value = "select notification_id from payment_audit_notification_manual_resends where id=:id", nativeQuery = true)
    Optional<Long> findNotificationIdForRecovery(long id);
}
