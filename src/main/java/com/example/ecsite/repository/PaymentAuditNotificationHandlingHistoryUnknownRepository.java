package com.example.ecsite.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.ecsite.entity.PaymentAuditNotificationHandlingHistoryUnknown;
import com.example.ecsite.entity.PaymentAuditNotificationHandlingHistoryUnknownId;

public interface PaymentAuditNotificationHandlingHistoryUnknownRepository extends JpaRepository<PaymentAuditNotificationHandlingHistoryUnknown, PaymentAuditNotificationHandlingHistoryUnknownId> {
    List<PaymentAuditNotificationHandlingHistoryUnknown> findByHistoryIdOrderByAttemptNoAsc(Long historyId);
}
