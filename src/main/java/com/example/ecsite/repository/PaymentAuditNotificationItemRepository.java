package com.example.ecsite.repository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import com.example.ecsite.repository.projection.AdminPaymentAuditNotificationItemProjection;
import java.util.Optional;
import com.example.ecsite.entity.PaymentAuditNotificationItem;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAuditNotificationItemRepository extends JpaRepository<PaymentAuditNotificationItem, Long> {
    List<PaymentAuditNotificationItem> findByNotificationIdOrderByIdAsc(Long notificationId);
    Optional<PaymentAuditNotificationItem> findByStateWarningTypeAndEpisodeNo(
        PaymentAuditNotificationWarningType warningType, long episodeNo);

    /** Single batch for the current page; warning type comes from the item's stored foreign key. */
    @Query("""
        select i.notification.id as notificationId, i.state.warningType as warningType,
            i.episodeNo as episodeNo, i.itemStatus as itemStatus, i.firstObservedAt as firstObservedAt,
            i.relatedAt as relatedAt, i.warningCount as warningCount, i.errorCode as errorCode,
            i.resolvedAt as resolvedAt, i.removedAt as removedAt
        from PaymentAuditNotificationItem i where i.notification.id in :notificationIds
        order by i.notification.id, i.id
        """)
    List<AdminPaymentAuditNotificationItemProjection> findAllForAdminByNotificationIds(List<Long> notificationIds);
}
