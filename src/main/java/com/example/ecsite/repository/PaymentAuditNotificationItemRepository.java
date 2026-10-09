package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;
import com.example.ecsite.entity.PaymentAuditNotificationItem;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAuditNotificationItemRepository extends JpaRepository<PaymentAuditNotificationItem, Long> {
    List<PaymentAuditNotificationItem> findByNotificationIdOrderByIdAsc(Long notificationId);
    Optional<PaymentAuditNotificationItem> findByStateWarningTypeAndEpisodeNo(
        PaymentAuditNotificationWarningType warningType, long episodeNo);
}
