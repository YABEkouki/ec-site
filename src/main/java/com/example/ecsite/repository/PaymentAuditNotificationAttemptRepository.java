package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.example.ecsite.entity.PaymentAuditNotificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAuditNotificationAttemptRepository extends JpaRepository<PaymentAuditNotificationAttempt, Long> {
    List<PaymentAuditNotificationAttempt> findByNotificationIdOrderByAttemptNoAsc(Long notificationId);
    Optional<PaymentAuditNotificationAttempt> findByNotificationIdAndClaimToken(Long notificationId, UUID claimToken);
}
