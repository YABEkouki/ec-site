package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.example.ecsite.entity.PaymentAuditNotificationManualResendAttempt;

public interface PaymentAuditNotificationManualResendAttemptRepository extends JpaRepository<PaymentAuditNotificationManualResendAttempt, Long> {
    Optional<PaymentAuditNotificationManualResendAttempt> findByManualResendId(Long manualResendId);

    @Query(value = """
        select id from payment_audit_notification_manual_resend_attempts
        where notification_id=:notificationId and result='UNKNOWN' order by id
        """, nativeQuery = true)
    List<Long> findUnknownIdsByNotificationId(long notificationId);
    /** Lock only after original notification and manual request. */
    @Query(value = "select * from payment_audit_notification_manual_resend_attempts where manual_resend_id=:id for update", nativeQuery = true)
    Optional<PaymentAuditNotificationManualResendAttempt> findForDelivery(long id);
}
