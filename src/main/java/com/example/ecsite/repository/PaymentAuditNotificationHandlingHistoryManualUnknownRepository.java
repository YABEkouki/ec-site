package com.example.ecsite.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.ecsite.entity.PaymentAuditNotificationHandlingHistoryManualUnknown;
import com.example.ecsite.entity.PaymentAuditNotificationHandlingHistoryManualUnknownId;

public interface PaymentAuditNotificationHandlingHistoryManualUnknownRepository
        extends JpaRepository<PaymentAuditNotificationHandlingHistoryManualUnknown, PaymentAuditNotificationHandlingHistoryManualUnknownId> {
    List<PaymentAuditNotificationHandlingHistoryManualUnknown> findByHistoryIdOrderByManualAttemptIdAsc(Long historyId);
}
