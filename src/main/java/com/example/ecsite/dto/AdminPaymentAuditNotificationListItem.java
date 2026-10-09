package com.example.ecsite.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.example.ecsite.entity.PaymentAuditNotificationStatus;
import com.example.ecsite.entity.PaymentAuditNotificationWarningType;

public record AdminPaymentAuditNotificationListItem(Long id, LocalDateTime createdAt,
        PaymentAuditNotificationStatus status, List<PaymentAuditNotificationWarningType> warningTypes,
        int warningItemCount, int attemptCount, int maxAttempts, LocalDateTime sentAt,
        LocalDateTime nextAttemptAt, boolean deliveryUncertain) {
    public AdminPaymentAuditNotificationListItem { warningTypes = List.copyOf(warningTypes); }
}
