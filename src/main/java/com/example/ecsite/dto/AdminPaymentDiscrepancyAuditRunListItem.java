package com.example.ecsite.dto;

import java.time.LocalDateTime;
import java.time.ZoneId;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRun;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunStatus;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunErrorCode;

public record AdminPaymentDiscrepancyAuditRunListItem(
        Long id, LocalDateTime startedAt, LocalDateTime finishedAt,
        PaymentDiscrepancyAuditRunStatus status, String instanceId,
        Integer candidateCount, Integer successCount, Integer inconsistentCount,
        Integer inProgressCount, Integer skippedCount, Integer failureCount,
        Integer unprocessedCount, Long durationMs,
        PaymentDiscrepancyAuditRunErrorCode errorCode, String errorSummary) {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    public static AdminPaymentDiscrepancyAuditRunListItem from(PaymentDiscrepancyAuditRun run) {
        boolean running = run.getStatus() == PaymentDiscrepancyAuditRunStatus.RUNNING;
        return new AdminPaymentDiscrepancyAuditRunListItem(run.getId(),
            LocalDateTime.ofInstant(run.getStartedAt(), TOKYO),
            run.getFinishedAt() == null ? null : LocalDateTime.ofInstant(run.getFinishedAt(), TOKYO),
            run.getStatus(), run.getInstanceId(), run.getCandidateCount(),
            running ? null : run.getSuccessCount(), running ? null : run.getInconsistentCount(),
            running ? null : run.getInProgressCount(), running ? null : run.getSkippedCount(),
            running ? null : run.getFailureCount(), running ? null : run.getUnprocessedCount(),
            run.getDurationMs(), run.getErrorCode(),
            run.getErrorCode() == null ? null : run.getErrorCode().summary());
    }
}
