package com.example.ecsite.entity;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditRunSummary;

class PaymentDiscrepancyAuditRunTest {
    private static final Instant START = Instant.parse("2026-10-08T00:00:00Z");

    @Test void createsRunningWithUnknownCandidates() {
        var run = PaymentDiscrepancyAuditRun.start("process", START);
        assertThat(run.getExecutionType()).isEqualTo(PaymentDiscrepancyAuditExecutionType.SCHEDULED);
        assertThat(run.getStatus()).isEqualTo(PaymentDiscrepancyAuditRunStatus.RUNNING);
        assertThat(run.getCandidateCount()).isNull();
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.getDurationMs()).isNull();
        assertThat(run.getProcessedCount()).isZero();
        assertThat(run.getUnprocessedCount()).isNull();
    }

    @ParameterizedTest @MethodSource("validSummaries")
    void endsWithValidatedCounts(PaymentDiscrepancyAuditRunSummary summary) {
        var run = PaymentDiscrepancyAuditRun.start("process", START);
        // Wall clock rollback is allowed; elapsed time is supplied separately.
        run.finish(START.minusSeconds(1), summary);
        assertThat(run.getStatus()).isEqualTo(summary.status());
        assertThat(run.getFinishedAt()).isEqualTo(START.minusSeconds(1));
        assertThat(run.getDurationMs()).isEqualTo(summary.durationMs());
        assertThat(run.getProcessedCount()).isEqualTo(summary.processedCount());
        assertThat(run.getUnprocessedCount()).isEqualTo(summary.unprocessedCount());
        assertThat(run.getInconsistentCount()).isEqualTo(summary.inconsistentCount());
        assertThat(run.getInProgressCount()).isEqualTo(summary.inProgressCount());
        assertThatThrownBy(() -> run.finish(START, summary)).isInstanceOf(IllegalStateException.class);
    }
    static Stream<PaymentDiscrepancyAuditRunSummary> validSummaries() {
        return Stream.of(
            summary(PaymentDiscrepancyAuditRunStatus.SUCCESS, 0, 0, 0, 0, 0, 0, 0, null),
            summary(PaymentDiscrepancyAuditRunStatus.SUCCESS, 3, 3, 1, 1, 0, 0, 10, null),
            summary(PaymentDiscrepancyAuditRunStatus.SUCCESS, 2, 0, 0, 0, 2, 0, 10, null),
            summary(PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE, 2, 0, 0, 0, 1, 1, 10, PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE),
            summary(PaymentDiscrepancyAuditRunStatus.FAILED, 2, 0, 0, 0, 0, 2, 10, PaymentDiscrepancyAuditRunErrorCode.ALL_ITEMS_FAILED),
            summary(PaymentDiscrepancyAuditRunStatus.FAILED, null, 0, 0, 0, 0, 0, 10, PaymentDiscrepancyAuditRunErrorCode.CANDIDATE_FETCH_FAILED),
            summary(PaymentDiscrepancyAuditRunStatus.FAILED, 3, 1, 0, 0, 0, 0, 10, PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED));
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2,3,4,5})
    void rejectsNegativeCounts(int field) {
        int[] c = {1, 0, 0, 0, 0, 0}; c[field] = -1;
        assertThatThrownBy(() -> summary(PaymentDiscrepancyAuditRunStatus.FAILED,
            c[0],c[1],c[2],c[3],c[4],c[5],0,PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2,3,4,5,6,7,8,9})
    void rejectsInvalidSummary(int kind) {
        assertThatThrownBy(() -> {
            switch (kind) {
                case 0 -> summary(PaymentDiscrepancyAuditRunStatus.RUNNING,0,0,0,0,0,0,0,null);
                case 1 -> summary(PaymentDiscrepancyAuditRunStatus.SUCCESS,null,0,0,0,0,0,0,null);
                case 2 -> summary(PaymentDiscrepancyAuditRunStatus.SUCCESS,1,0,0,0,0,0,0,null);
                case 3 -> summary(PaymentDiscrepancyAuditRunStatus.SUCCESS,1,0,0,0,0,1,0,null);
                case 4 -> summary(PaymentDiscrepancyAuditRunStatus.SUCCESS,1,1,1,1,0,0,0,null);
                case 5 -> summary(PaymentDiscrepancyAuditRunStatus.FAILED,null,1,0,0,0,0,0,PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED);
                case 6 -> summary(PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE,1,0,0,0,0,1,0,PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE);
                case 7 -> summary(PaymentDiscrepancyAuditRunStatus.SUCCESS,0,0,0,0,0,0,-1,null);
                case 8 -> summary(PaymentDiscrepancyAuditRunStatus.FAILED,1,Integer.MAX_VALUE,0,0,Integer.MAX_VALUE,0,0,PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED);
                default -> summary(PaymentDiscrepancyAuditRunStatus.FAILED,0,0,0,0,0,0,0,null);
            }
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void safeErrorsAreFixedTextAndInvalidFinishLeavesRunUntouched() {
        var run = PaymentDiscrepancyAuditRun.start("process", START);
        assertThatThrownBy(() -> run.finish(null, validSummaries().findFirst().orElseThrow()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(run.getStatus()).isEqualTo(PaymentDiscrepancyAuditRunStatus.RUNNING);
        for (var code : PaymentDiscrepancyAuditRunErrorCode.values()) {
            assertThat(code.summary()).isNotBlank().hasSizeLessThanOrEqualTo(1000);
        }
        var summary = summary(PaymentDiscrepancyAuditRunStatus.FAILED,null,0,0,0,0,0,1,
            PaymentDiscrepancyAuditRunErrorCode.CANDIDATE_FETCH_FAILED);
        run.finish(START,summary);
        assertThat(run.getErrorSummary()).isEqualTo(summary.errorCode().summary());
    }

    @Test void rejectsInvalidStartArguments() {
        assertThatThrownBy(() -> PaymentDiscrepancyAuditRun.start(" ",START)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentDiscrepancyAuditRun.start("x".repeat(101),START)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentDiscrepancyAuditRun.start("process",null)).isInstanceOf(IllegalArgumentException.class);
    }
    private static PaymentDiscrepancyAuditRunSummary summary(PaymentDiscrepancyAuditRunStatus status,
        Integer n,int s,int i,int p,int k,int f,long d,PaymentDiscrepancyAuditRunErrorCode e) {
        return new PaymentDiscrepancyAuditRunSummary(status,n,s,i,p,k,f,d,e);
    }
}
