package com.example.ecsite.dto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

public record AdminPaymentDiscrepancyAuditMonitoringSummary(
        AdminPaymentDiscrepancyAuditRunListItem latestRun,
        LocalDateTime lastSuccessFinishedAt, long openCount, long longUnhandledCount,
        boolean schedulerEnabled, LocalDateTime evaluatedAt, Duration longUnhandledAge,
        List<AdminPaymentDiscrepancyAuditWarning> warnings) {
    public AdminPaymentDiscrepancyAuditMonitoringSummary {
        warnings = List.copyOf(warnings);
    }
}
