package com.example.ecsite.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.example.ecsite.entity.PaymentAuditNotificationStatus;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;

public record AdminPaymentAuditNotificationListItem(Long id, LocalDateTime createdAt,
        PaymentAuditNotificationStatus status, List<PaymentAuditNotificationWarningType> warningTypes,
        int warningItemCount, int attemptCount, int maxAttempts, LocalDateTime sentAt,
        LocalDateTime nextAttemptAt, boolean deliveryUncertain,
        boolean actionable, com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus handlingStatus,
        boolean requiresRecheck, LocalDateTime handlingUpdatedAt) {
    public AdminPaymentAuditNotificationListItem(Long id, LocalDateTime createdAt,
        PaymentAuditNotificationStatus status, List<PaymentAuditNotificationWarningType> warningTypes,
        int warningItemCount, int attemptCount, int maxAttempts, LocalDateTime sentAt,
        LocalDateTime nextAttemptAt, boolean deliveryUncertain) {
        this(id,createdAt,status,warningTypes,warningItemCount,attemptCount,maxAttempts,sentAt,nextAttemptAt,deliveryUncertain,
            status == PaymentAuditNotificationStatus.EXHAUSTED || deliveryUncertain,
            com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.UNREVIEWED,false,null);
    }
    public AdminPaymentAuditNotificationListItem { warningTypes = List.copyOf(warningTypes); }
}
