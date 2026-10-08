package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import com.example.ecsite.config.PaymentDiscrepancyAuditProperties;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.payment.PaymentFlowStatus;

class PaymentDiscrepancyAuditServiceRunTest {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentDiscrepancyAuditRetryFacade facade = mock(PaymentDiscrepancyAuditRetryFacade.class);
    private final PaymentDiscrepancyAuditRunRecordingService recording = mock(PaymentDiscrepancyAuditRunRecordingService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);
    private final PaymentDiscrepancyAuditProperties properties = new PaymentDiscrepancyAuditProperties(true,Duration.ofMinutes(5),100);
    private final PaymentDiscrepancyAuditService service = new PaymentDiscrepancyAuditService(payments,facade,properties,recording,clock);

    @Test void recordsEmptyBatch() {
        service.auditPayments();
        var summary = finished();
        assertThat(summary.status()).isEqualTo(PaymentDiscrepancyAuditRunStatus.SUCCESS);
        assertThat(summary.candidateCount()).isZero();
        assertThat(summary.processedCount()).isZero();
        verifyNoInteractions(facade);
    }

    @ParameterizedTest @EnumSource(PaymentDiscrepancyAuditResult.Status.class)
    void countsFinalResultOnce(PaymentDiscrepancyAuditResult.Status status) {
        candidates(1L); when(facade.auditWithResult(1L)).thenReturn(result(status));
        service.auditPayments();
        var summary = finished();
        assertThat(summary.status()).isEqualTo(PaymentDiscrepancyAuditRunStatus.SUCCESS);
        assertThat(summary.candidateCount()).isEqualTo(1);
        assertThat(summary.successCount()).isEqualTo(status == PaymentDiscrepancyAuditResult.Status.SKIPPED ? 0:1);
        assertThat(summary.skippedCount()).isEqualTo(status == PaymentDiscrepancyAuditResult.Status.SKIPPED ? 1:0);
        assertThat(summary.inconsistentCount()).isEqualTo(status == PaymentDiscrepancyAuditResult.Status.INCONSISTENT ? 1:0);
        assertThat(summary.inProgressCount()).isEqualTo(status == PaymentDiscrepancyAuditResult.Status.IN_PROGRESS ? 1:0);
        assertThat(summary.durationMs()).isNotNegative();
        var order=inOrder(recording,payments,facade);
        order.verify(recording).startRun();
        order.verify(payments).findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(any(),any(),eq(0L),any());
        order.verify(facade).auditWithResult(1L);
        order.verify(recording).finishRun(anyLong(),eq(clock.instant()),any());
        verify(facade,times(1)).auditWithResult(1L);
    }

    @Test void countsMixedResultsAndContinuesAfterFailure() {
        candidates(1L,2L,3L,4L,5L);
        when(facade.auditWithResult(1L)).thenReturn(result(PaymentDiscrepancyAuditResult.Status.CONSISTENT));
        when(facade.auditWithResult(2L)).thenThrow(new IllegalStateException("secret must not be persisted"));
        when(facade.auditWithResult(3L)).thenReturn(result(PaymentDiscrepancyAuditResult.Status.INCONSISTENT));
        when(facade.auditWithResult(4L)).thenReturn(result(PaymentDiscrepancyAuditResult.Status.IN_PROGRESS));
        when(facade.auditWithResult(5L)).thenReturn(result(PaymentDiscrepancyAuditResult.Status.SKIPPED));
        service.auditPayments();
        var s=finished();
        assertThat(s.status()).isEqualTo(PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE);
        assertThat(s.errorCode()).isEqualTo(PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE);
        assertThat(s.successCount()).isEqualTo(3);
        assertThat(s.inconsistentCount()).isEqualTo(1);
        assertThat(s.inProgressCount()).isEqualTo(1);
        assertThat(s.skippedCount()).isEqualTo(1);
        assertThat(s.failureCount()).isEqualTo(1);
        assertThat(s.unprocessedCount()).isZero();
    }

    @Test void allIndividualFailuresHaveDedicatedClassification() {
        candidates(1L,2L);
        when(facade.auditWithResult(anyLong())).thenThrow(new IllegalStateException("failed"));
        service.auditPayments();
        var s=finished();
        assertThat(s.status()).isEqualTo(PaymentDiscrepancyAuditRunStatus.FAILED);
        assertThat(s.errorCode()).isEqualTo(PaymentDiscrepancyAuditRunErrorCode.ALL_ITEMS_FAILED);
        assertThat(s.failureCount()).isEqualTo(2);
    }

    @Test void candidateFailureIsRecordedAndPropagated() {
        var failure = new IllegalStateException("fetch failed");
        when(payments.findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(any(),any(),anyLong(),any()))
            .thenThrow(failure);
        assertThatThrownBy(service::auditPayments).isSameAs(failure);
        var s=finished();
        assertThat(s.status()).isEqualTo(PaymentDiscrepancyAuditRunStatus.FAILED);
        assertThat(s.errorCode()).isEqualTo(PaymentDiscrepancyAuditRunErrorCode.CANDIDATE_FETCH_FAILED);
        assertThat(s.candidateCount()).isNull();
        verifyNoInteractions(facade);
    }

    @Test void startFailurePreventsCandidateFetchAndAudits() {
        var failure = new IllegalStateException("start failed");
        when(recording.startRun()).thenThrow(failure);
        assertThatThrownBy(service::auditPayments).isSameAs(failure);
        verifyNoInteractions(payments,facade);
        verify(recording,times(1)).startRun();
        verify(recording,never()).finishRun(any(),any(),any());
    }

    @Test void finishFailureDoesNotRepeatAuditOrLoseCursorProgress() {
        candidates(1L); when(facade.auditWithResult(1L)).thenReturn(result(PaymentDiscrepancyAuditResult.Status.CONSISTENT));
        var failure=new IllegalStateException("finish failed");
        doThrow(failure).when(recording).finishRun(anyLong(),any(),any());
        assertThatThrownBy(service::auditPayments).isSameAs(failure);
        assertThat(finished().status()).isEqualTo(PaymentDiscrepancyAuditRunStatus.SUCCESS);
        verify(facade,times(1)).auditWithResult(1L);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(service,"lastPaymentId")).isEqualTo(1L);
    }

    @Test void unexpectedWholeRunErrorRetainsCompletedCountsAndPropagates() {
        candidates(1L,2L);
        when(facade.auditWithResult(1L)).thenReturn(result(PaymentDiscrepancyAuditResult.Status.CONSISTENT));
        var failure = new AssertionError("unexpected interruption");
        when(facade.auditWithResult(2L)).thenThrow(failure);
        assertThatThrownBy(service::auditPayments).isSameAs(failure);
        var s=finished();
        assertThat(s.errorCode()).isEqualTo(PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED);
        assertThat(s.candidateCount()).isEqualTo(2);
        assertThat(s.successCount()).isEqualTo(1);
        assertThat(s.failureCount()).isZero();
        assertThat(s.unprocessedCount()).isEqualTo(1);
    }

    @Test void preservesBothOriginalAndFinishFailures() {
        var original = new IllegalStateException("fetch failed"); var save=new IllegalStateException("save failed");
        when(payments.findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(any(),any(),anyLong(),any())).thenThrow(original);
        doThrow(save).when(recording).finishRun(anyLong(),any(),any());
        assertThatThrownBy(service::auditPayments).isSameAs(original);
        assertThat(original.getSuppressed()).containsExactly(save);
        verify(recording,times(1)).finishRun(anyLong(),any(),any());
    }

    @Test void realRetryFacadeCountsOnlyRecoveredResult() {
        candidates(1L);
        var item=mock(PaymentDiscrepancyAuditItemService.class);
        var conflict=new org.springframework.orm.ObjectOptimisticLockingFailureException(PaymentDiscrepancy.class,1L);
        when(item.auditWithResult(1L)).thenThrow(conflict).thenReturn(result(PaymentDiscrepancyAuditResult.Status.INCONSISTENT));
        new PaymentDiscrepancyAuditService(payments,new PaymentDiscrepancyAuditRetryFacade(item),properties,recording,clock).auditPayments();
        var s=finished(); assertThat(s.successCount()).isEqualTo(1); assertThat(s.failureCount()).isZero();
        verify(item,times(2)).auditWithResult(1L);
    }

    @Test void realRetryExhaustionCountsOneFailure() {
        candidates(1L);
        var item=mock(PaymentDiscrepancyAuditItemService.class);
        when(item.auditWithResult(1L)).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(PaymentDiscrepancy.class,1L));
        new PaymentDiscrepancyAuditService(payments,new PaymentDiscrepancyAuditRetryFacade(item),properties,recording,clock).auditPayments();
        assertThat(finished().failureCount()).isEqualTo(1);
        verify(item,times(3)).auditWithResult(1L);
    }

    private void candidates(Long... ids) {
        List<Payment> list=java.util.Arrays.stream(ids).map(id -> { var p=mock(Payment.class); when(p.getId()).thenReturn(id); return p; }).toList();
        when(payments.findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(any(),any(),anyLong(),any(Pageable.class))).thenReturn(list);
    }
    private PaymentDiscrepancyAuditRunSummary finished() {
        var c=ArgumentCaptor.forClass(PaymentDiscrepancyAuditRunSummary.class);
        verify(recording,times(1)).finishRun(anyLong(),eq(clock.instant()),c.capture());
        return c.getValue();
    }
    private PaymentDiscrepancyAuditResult result(PaymentDiscrepancyAuditResult.Status status) {
        return new PaymentDiscrepancyAuditResult(status,status==PaymentDiscrepancyAuditResult.Status.SKIPPED ? PaymentDiscrepancyAuditResult.SkipReason.PENDING_TRANSACTION:null,PaymentFlowStatus.SUCCEEDED,List.of());
    }
}
