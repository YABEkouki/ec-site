package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecsite.entity.PaymentDiscrepancyAuditRunErrorCode;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentDiscrepancyAuditRunSummaryTest {
    @ParameterizedTest
    @EnumSource(value = PaymentDiscrepancyAuditRunErrorCode.class,
        names = {"ALL_ITEMS_FAILED", "CANDIDATE_FETCH_FAILED", "EXECUTION_ABORTED"})
    void partialFailureRejectsContradictoryCodes(PaymentDiscrepancyAuditRunErrorCode code) {
        assertThatThrownBy(() -> summary(PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE, 2, 1, 0, 1, code))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void allItemsFailedRequiresPositiveKnownCandidatesAndEveryItemFailed() {
        var code = PaymentDiscrepancyAuditRunErrorCode.ALL_ITEMS_FAILED;
        assertThat(summary(PaymentDiscrepancyAuditRunStatus.FAILED, 2, 0, 0, 2, code).failureCount()).isEqualTo(2);
        assertThatThrownBy(() -> summary(PaymentDiscrepancyAuditRunStatus.FAILED, null, 0, 0, 0, code))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> summary(PaymentDiscrepancyAuditRunStatus.FAILED, 0, 0, 0, 0, code))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> summary(PaymentDiscrepancyAuditRunStatus.FAILED, 2, 0, 0, 1, code))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> summary(PaymentDiscrepancyAuditRunStatus.FAILED, 2, 1, 0, 1, code))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void candidateFetchFailureRequiresUnknownCandidates() {
        var code = PaymentDiscrepancyAuditRunErrorCode.CANDIDATE_FETCH_FAILED;
        assertThat(summary(PaymentDiscrepancyAuditRunStatus.FAILED, null, 0, 0, 0, code).candidateCount()).isNull();
        assertThatThrownBy(() -> summary(PaymentDiscrepancyAuditRunStatus.FAILED, 0, 0, 0, 0, code))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @EnumSource(PaymentDiscrepancyAuditRunErrorCode.class)
    void successRejectsAnyErrorCode(PaymentDiscrepancyAuditRunErrorCode code) {
        assertThatThrownBy(() -> summary(PaymentDiscrepancyAuditRunStatus.SUCCESS, 1, 1, 0, 0, code))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void itemFailureRequiresCompletedPartialFailure() {
        var code = PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE;
        assertThat(summary(PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE, 2, 1, 0, 1, code).failureCount()).isEqualTo(1);
        assertThat(summary(PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE, 2, 0, 1, 1, code).skippedCount()).isEqualTo(1);
        assertThatThrownBy(() -> summary(PaymentDiscrepancyAuditRunStatus.FAILED, 2, 0, 0, 2, code))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void abortedExecutionAllowsCompletedAndUnprocessedCandidates(int successes) {
        var result = summary(PaymentDiscrepancyAuditRunStatus.FAILED, 2, successes, 0, 0,
            PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED);
        assertThat(result.unprocessedCount()).isEqualTo(2 - successes);
    }

    @Test void abortedExecutionAllowsZeroCandidatesAndAllItemsFailedBeforeOverallException() {
        var code = PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED;
        assertThat(summary(PaymentDiscrepancyAuditRunStatus.FAILED, 0, 0, 0, 0, code).unprocessedCount()).isZero();
        assertThat(summary(PaymentDiscrepancyAuditRunStatus.FAILED, 2, 0, 0, 2, code).unprocessedCount()).isZero();
    }

    private PaymentDiscrepancyAuditRunSummary summary(PaymentDiscrepancyAuditRunStatus status,
            Integer candidates, int success, int skipped, int failed, PaymentDiscrepancyAuditRunErrorCode code) {
        return new PaymentDiscrepancyAuditRunSummary(status, candidates, success, 0, 0, skipped, failed, 1, code);
    }
}
