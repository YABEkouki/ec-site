package com.example.ecsite.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.ecsite.entity.PaymentAuditNotificationHandlingHistory;

public interface PaymentAuditNotificationHandlingHistoryRepository extends JpaRepository<PaymentAuditNotificationHandlingHistory, Long> {
    List<PaymentAuditNotificationHandlingHistory> findByNotificationIdOrderByChangedAtAscIdAsc(Long notificationId);
}
