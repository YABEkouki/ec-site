package com.example.ecsite.repository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import com.example.ecsite.repository.projection.AdminPaymentAuditNotificationAttemptProjection;
import java.util.Optional;
import java.util.UUID;
import com.example.ecsite.entity.PaymentAuditNotificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAuditNotificationAttemptRepository extends JpaRepository<PaymentAuditNotificationAttempt, Long> {
    List<PaymentAuditNotificationAttempt> findByNotificationIdOrderByAttemptNoAsc(Long notificationId);
    Optional<PaymentAuditNotificationAttempt> findByNotificationIdAndClaimToken(Long notificationId, UUID claimToken);

    @Query("""
        select a.attemptNo as attemptNo, a.result as result, a.startedAt as startedAt,
            a.finishedAt as finishedAt, a.failureCode as failureCode
        from PaymentAuditNotificationAttempt a where a.notification.id = :notificationId
        order by a.attemptNo asc
        """)
    List<AdminPaymentAuditNotificationAttemptProjection> findForAdminByNotificationId(Long notificationId);
}
