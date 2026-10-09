package com.example.ecsite.repository.projection;

import java.time.Instant;
import com.example.ecsite.entity.*;

/** Scalar history view: addresses, message text and claim credentials are never selected. */
public interface AdminPaymentAuditNotificationProjection {
    Long getId();
    PaymentAuditNotificationStatus getStatus();
    Instant getCreatedAt();
    Instant getUpdatedAt();
    Instant getEvaluatedAt();
    Integer getAttemptCount();
    Integer getMaxAttempts();
    Instant getSentAt();
    Instant getNextAttemptAt();
    Instant getClosedAt();
    PaymentAuditNotificationCloseReason getCloseReason();
    Boolean getDeliveryUncertain();
    Integer getRecipientCount();
}
