package com.example.ecsite.repository;

import com.example.ecsite.entity.PaymentAuditNotificationState;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface PaymentAuditNotificationStateRepository extends JpaRepository<PaymentAuditNotificationState, PaymentAuditNotificationWarningType> {
    /** Every observer/delivery preflight must acquire these six rows before notification locks. */
    @Query(value = "select * from payment_audit_notification_states order by warning_type for update", nativeQuery = true)
    List<PaymentAuditNotificationState> findAllForObservation();
}
