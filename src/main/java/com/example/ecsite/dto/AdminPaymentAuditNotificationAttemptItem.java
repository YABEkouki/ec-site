package com.example.ecsite.dto;

import java.time.LocalDateTime;
import com.example.ecsite.entity.PaymentAuditNotificationAttemptResult;
import com.example.ecsite.entity.PaymentAuditNotificationFailureCode;

public record AdminPaymentAuditNotificationAttemptItem(int attemptNo, PaymentAuditNotificationAttemptResult result,
        LocalDateTime startedAt, LocalDateTime finishedAt, PaymentAuditNotificationFailureCode failureCode) {}
