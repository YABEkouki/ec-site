package com.example.ecsite.repository;

import com.example.ecsite.entity.PaymentAuditNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
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
}
