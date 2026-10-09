package com.example.ecsite.repository.projection;

import java.time.Instant;
import com.example.ecsite.entity.*;

public interface AdminPaymentAuditNotificationItemProjection {
    Long getNotificationId();
    PaymentAuditNotificationWarningType getWarningType();
    Long getEpisodeNo();
    PaymentAuditNotificationItemStatus getItemStatus();
    Instant getFirstObservedAt();
    Instant getRelatedAt();
    Long getWarningCount();
    PaymentDiscrepancyAuditRunErrorCode getErrorCode();
    Instant getResolvedAt();
    Instant getRemovedAt();
}
