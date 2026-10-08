package com.example.ecsite.service.payment;

import com.example.ecsite.entity.PaymentDiscrepancyAuditRunErrorCode;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunStatus;

/** Final committed outcomes, not retry attempts. IN_PROGRESS is a success subcount. */
public record PaymentDiscrepancyAuditRunSummary(
        PaymentDiscrepancyAuditRunStatus status,
        Integer candidateCount,
        int successCount,
        int inconsistentCount,
        int inProgressCount,
        int skippedCount,
        int failureCount,
        long durationMs,
        PaymentDiscrepancyAuditRunErrorCode errorCode) {

    public PaymentDiscrepancyAuditRunSummary {
        if (status == null || status == PaymentDiscrepancyAuditRunStatus.RUNNING) {
            throw new IllegalArgumentException("A final audit run status is required");
        }
        if ((candidateCount != null && candidateCount < 0) || successCount < 0
                || inconsistentCount < 0 || inProgressCount < 0 || skippedCount < 0
                || failureCount < 0 || durationMs < 0) {
            throw new IllegalArgumentException("Counts and duration must be nonnegative");
        }
        long processed = (long) successCount + skippedCount + failureCount;
        if ((long) inconsistentCount + inProgressCount > successCount
                || (candidateCount == null && processed != 0)
                || (candidateCount != null && processed > candidateCount)) {
            throw new IllegalArgumentException("Audit run counts are inconsistent");
        }
        if (status == PaymentDiscrepancyAuditRunStatus.SUCCESS
                && (candidateCount == null || failureCount != 0 || processed != candidateCount)) {
            throw new IllegalArgumentException("Success requires all candidates to be processed without failure");
        }
        if (status == PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE
                && (candidateCount == null || failureCount == 0
                    || (long) successCount + skippedCount == 0 || processed != candidateCount)) {
            throw new IllegalArgumentException("Partial failure requires both failed and nonfailed processed candidates");
        }
        if ((status == PaymentDiscrepancyAuditRunStatus.SUCCESS && errorCode != null)
                || (status != PaymentDiscrepancyAuditRunStatus.SUCCESS && errorCode == null)) {
            throw new IllegalArgumentException("Error classification must match the run outcome");
        }
        if (errorCode != null) {
            boolean compatible = switch (errorCode) {
                case ALL_ITEMS_FAILED -> status == PaymentDiscrepancyAuditRunStatus.FAILED
                        && candidateCount != null && candidateCount > 0 && failureCount == candidateCount;
                case CANDIDATE_FETCH_FAILED -> status == PaymentDiscrepancyAuditRunStatus.FAILED
                        && candidateCount == null;
                case ITEM_FAILURE -> status == PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE;
                // A whole-run exception can occur even after every candidate was processed.
                // Counts alone cannot establish where that exception occurred.
                case EXECUTION_ABORTED -> status == PaymentDiscrepancyAuditRunStatus.FAILED;
            };
            if (!compatible) {
                throw new IllegalArgumentException("Error classification contradicts the run status or counts");
            }
        }
    }

    public long processedCount() {
        return (long) successCount + skippedCount + failureCount;
    }

    public Integer unprocessedCount() {
        return candidateCount == null ? null : (int) (candidateCount - processedCount());
    }
}
