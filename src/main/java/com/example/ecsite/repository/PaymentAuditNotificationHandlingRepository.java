package com.example.ecsite.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.example.ecsite.entity.PaymentAuditNotificationHandling;

public interface PaymentAuditNotificationHandlingRepository extends JpaRepository<PaymentAuditNotificationHandling, Long> {
    @org.springframework.data.jpa.repository.Query(value = "select * from payment_audit_notifications where id=:id for update", nativeQuery = true)
    java.util.Optional<com.example.ecsite.entity.PaymentAuditNotification> lockNotification(long id);

    @org.springframework.data.jpa.repository.Query(value = "select attempt_no from payment_audit_notification_attempts where notification_id=:id and result='UNKNOWN' order by attempt_no", nativeQuery = true)
    java.util.List<Integer> findUnknownAttemptNumbers(long id);

    @org.springframework.data.jpa.repository.Query(value = """
        select exists(select 1 from payment_audit_notification_handlings h
        join payment_audit_notification_attempts a on a.notification_id=h.notification_id
        where h.notification_id=:id and h.handling_status='RESOLVED' and a.result='UNKNOWN'
        and not exists(select 1 from payment_audit_notification_handling_history_unknowns u
        where u.history_id=h.last_resolved_history_id and u.notification_id=h.notification_id and u.attempt_no=a.attempt_no))
        """, nativeQuery = true)
    boolean needsReconfirmation(long id);
}
