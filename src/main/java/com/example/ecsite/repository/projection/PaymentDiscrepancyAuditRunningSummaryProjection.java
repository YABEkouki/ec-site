package com.example.ecsite.repository.projection;

import java.time.Instant;

public interface PaymentDiscrepancyAuditRunningSummaryProjection {
    long getCount();
    Instant getOldestStartedAt();
}
