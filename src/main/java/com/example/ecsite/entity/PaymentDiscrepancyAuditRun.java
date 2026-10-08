package com.example.ecsite.entity;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditRunSummary;

@Entity
@Table(name = "payment_discrepancy_audit_runs")
public class PaymentDiscrepancyAuditRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_type", nullable = false, length = 20)
    private PaymentDiscrepancyAuditExecutionType executionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentDiscrepancyAuditRunStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "finished_at", nullable = true)
    private Instant finishedAt;

    @Column(name = "candidate_count", nullable = true)
    private Integer candidateCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "inconsistent_count", nullable = false)
    private int inconsistentCount;

    @Column(name = "in_progress_count", nullable = false)
    private int inProgressCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "duration_ms", nullable = true)
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", nullable = true, length = 50)
    private PaymentDiscrepancyAuditRunErrorCode errorCode;

    @Column(name = "error_summary", nullable = true, length = 1000)
    private String errorSummary;

    @Column(name = "instance_id", nullable = false, length = 100)
    private String instanceId;

    protected PaymentDiscrepancyAuditRun() {
    }

    public static PaymentDiscrepancyAuditRun start(String instanceId, Instant startedAt) {
        if (instanceId == null || instanceId.isBlank() || instanceId.length() > 100 || startedAt == null) {
            throw new IllegalArgumentException("Valid instance ID and start time are required");
        }
        var run = new PaymentDiscrepancyAuditRun();
        run.executionType = PaymentDiscrepancyAuditExecutionType.SCHEDULED;
        run.status = PaymentDiscrepancyAuditRunStatus.RUNNING;
        run.startedAt = startedAt;
        run.instanceId = instanceId;
        return run;
    }

    /** The recording service must hold the row lock before invoking this transition. */
    public void finish(Instant finishedAt, PaymentDiscrepancyAuditRunSummary summary) {
        if (status != PaymentDiscrepancyAuditRunStatus.RUNNING) {
            throw new IllegalStateException("Audit run has already finished");
        }
        if (finishedAt == null || summary == null) {
            throw new IllegalArgumentException("Finish time and validated summary are required");
        }
        this.finishedAt = finishedAt;
        this.status = summary.status();
        this.candidateCount = summary.candidateCount();
        this.successCount = summary.successCount();
        this.inconsistentCount = summary.inconsistentCount();
        this.inProgressCount = summary.inProgressCount();
        this.skippedCount = summary.skippedCount();
        this.failureCount = summary.failureCount();
        this.durationMs = summary.durationMs();
        this.errorCode = summary.errorCode();
        this.errorSummary = errorCode == null ? null : errorCode.summary();
    }

    public long getProcessedCount() {
        return (long) successCount + skippedCount + failureCount;
    }

    public Integer getUnprocessedCount() {
        return candidateCount == null ? null : (int) (candidateCount - getProcessedCount());
    }

    public Long getId() {
        return id;
    }

    public PaymentDiscrepancyAuditExecutionType getExecutionType() {
        return executionType;
    }

    public PaymentDiscrepancyAuditRunStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Integer getCandidateCount() {
        return candidateCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getInconsistentCount() {
        return inconsistentCount;
    }

    public int getInProgressCount() {
        return inProgressCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public PaymentDiscrepancyAuditRunErrorCode getErrorCode() {
        return errorCode;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public String getInstanceId() {
        return instanceId;
    }

}
