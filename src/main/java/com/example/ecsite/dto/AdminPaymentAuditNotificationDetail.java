package com.example.ecsite.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.example.ecsite.entity.PaymentAuditNotificationStatus;
import com.example.ecsite.entity.PaymentAuditNotificationCloseReason;

public record AdminPaymentAuditNotificationDetail(Long id, PaymentAuditNotificationStatus status,
        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime evaluatedAt,
        int attemptCount, int maxAttempts, LocalDateTime sentAt, LocalDateTime nextAttemptAt,
        LocalDateTime closedAt, PaymentAuditNotificationCloseReason closeReason, boolean deliveryUncertain,
        int recipientCount, List<AdminPaymentAuditNotificationWarningItem> warnings,
        List<AdminPaymentAuditNotificationAttemptItem> attempts, AdminPaymentAuditNotificationHandlingDetail handling) {
    public AdminPaymentAuditNotificationDetail(Long id, PaymentAuditNotificationStatus status,
        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime evaluatedAt,
        int attemptCount, int maxAttempts, LocalDateTime sentAt, LocalDateTime nextAttemptAt,
        LocalDateTime closedAt, PaymentAuditNotificationCloseReason closeReason, boolean deliveryUncertain,
        int recipientCount, List<AdminPaymentAuditNotificationWarningItem> warnings,
        List<AdminPaymentAuditNotificationAttemptItem> attempts) {
        this(id,status,createdAt,updatedAt,evaluatedAt,attemptCount,maxAttempts,sentAt,nextAttemptAt,
            closedAt,closeReason,deliveryUncertain,recipientCount,warnings,attempts,null);
    }
    public AdminPaymentAuditNotificationDetail {
        warnings = List.copyOf(warnings);
        attempts = List.copyOf(attempts);
    }
}
