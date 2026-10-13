package com.example.ecsite.dto;

import java.time.LocalDateTime;
import com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus;

public record AdminPaymentAuditNotificationHandlingHistoryItem(Long id,
    PaymentAuditNotificationHandlingStatus fromStatus, PaymentAuditNotificationHandlingStatus toStatus,
    LocalDateTime changedAt, Long actorUserId, String actorUsername, String comment) {}
