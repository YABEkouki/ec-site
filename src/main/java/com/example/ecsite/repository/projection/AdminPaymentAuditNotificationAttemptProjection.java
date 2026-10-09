package com.example.ecsite.repository.projection;

import java.time.Instant;
import com.example.ecsite.entity.PaymentAuditNotificationAttemptResult;
import com.example.ecsite.entity.PaymentAuditNotificationFailureCode;

public interface AdminPaymentAuditNotificationAttemptProjection {
    Integer getAttemptNo();
    PaymentAuditNotificationAttemptResult getResult();
    Instant getStartedAt();
    Instant getFinishedAt();
    PaymentAuditNotificationFailureCode getFailureCode();
}
