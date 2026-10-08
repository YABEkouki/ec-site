package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.form.AdminPaymentDiscrepancyAuditRunSearchForm;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditRunSummary;

class AdminPaymentDiscrepancyAuditRunServiceTest {
    final PaymentDiscrepancyAuditRunRepository runs = mock(PaymentDiscrepancyAuditRunRepository.class);
    final PaymentDiscrepancyRepository discrepancies = mock(PaymentDiscrepancyRepository.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
    final AdminPaymentDiscrepancyAuditRunService service = new AdminPaymentDiscrepancyAuditRunService(runs, discrepancies, clock,
        new com.example.ecsite.config.PaymentDiscrepancyAuditProperties(true,Duration.ofMinutes(5),100),
        new com.example.ecsite.config.PaymentDiscrepancyAuditMonitoringProperties(Duration.ofMinutes(15),Duration.ofMinutes(10),3,Duration.ofHours(24)));

    @org.junit.jupiter.api.BeforeEach void monitoringDefaults() {
        var aggregate=mock(com.example.ecsite.repository.projection.PaymentDiscrepancyAuditRunningSummaryProjection.class);
        org.mockito.Mockito.lenient().when(runs.summarizeLongRunning(any(),any())).thenReturn(aggregate);
    }
    @Test void runningIsUndeterminedAndSummaryIsIndependentOfSearch() {
        var run = PaymentDiscrepancyAuditRun.start("instance", clock.instant());
        when(runs.findFirstByExecutionTypeOrderByStartedAtDescIdDesc(any())).thenReturn(Optional.of(run));
        when(runs.findFirstByExecutionTypeAndStatusOrderByFinishedAtDescIdDesc(any(), any())).thenReturn(Optional.empty());
        when(discrepancies.countByStatus(PaymentDiscrepancyRecordStatus.OPEN)).thenReturn(4L);
        when(discrepancies.countLongUnhandled(any(), any(), any())).thenReturn(2L);
        var summary = service.monitoringSummary();
        assertThat(summary.latestRun().startedAt()).isEqualTo(LocalDateTime.of(2026,10,8,9,0));
        assertThat(summary.latestRun().successCount()).isNull();
        assertThat(summary.latestRun().candidateCount()).isNull();
        assertThat(summary.latestRun().unprocessedCount()).isNull();
        assertThat(summary.lastSuccessFinishedAt()).isNull();
        assertThat(summary.openCount()).isEqualTo(4);
        assertThat(summary.longUnhandledCount()).isEqualTo(2);
        verify(discrepancies).countLongUnhandled(PaymentDiscrepancyRecordStatus.OPEN,
            PaymentDiscrepancyHandlingStatus.COMPLETED, LocalDateTime.of(2026,10,7,9,0));
    }

    @Test void successfulDetectionCountsAreInnerCountsAndErrorIsFixedText() {
        var run = PaymentDiscrepancyAuditRun.start("instance", clock.instant());
        run.finish(clock.instant(), new PaymentDiscrepancyAuditRunSummary(PaymentDiscrepancyAuditRunStatus.SUCCESS, 3, 2, 1, 1, 1, 0, 10, null));
        when(runs.searchForAdmin(any(), isNull(), isNull(), isNull(), any())).thenReturn(new PageImpl<>(List.of(run)));
        var result = service.search(new AdminPaymentDiscrepancyAuditRunSearchForm(),0,20).getContent().getFirst();
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.inconsistentCount()).isEqualTo(1);
        assertThat(result.inProgressCount()).isEqualTo(1);
        assertThat(result.unprocessedCount()).isZero();
        assertThat(result.errorSummary()).isNull();
    }

    @Test void dateRangeUsesTokyoInclusiveFromExclusiveNextDayToAndClampsLastPage() {
        var form = new AdminPaymentDiscrepancyAuditRunSearchForm();
        form.setFrom(LocalDate.of(2026,10,8)); form.setTo(LocalDate.of(2026,10,8));
        form.setStatus(PaymentDiscrepancyAuditRunStatus.FAILED);
        when(runs.searchForAdmin(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Pageable page = inv.getArgument(4);
            return new PageImpl<PaymentDiscrepancyAuditRun>(List.of(), page, 21);
        });
        var result = service.search(form,99,20);
        assertThat(result.getNumber()).isEqualTo(1);
        verify(runs, times(2)).searchForAdmin(eq(PaymentDiscrepancyAuditExecutionType.SCHEDULED),
            eq(PaymentDiscrepancyAuditRunStatus.FAILED), eq(Instant.parse("2026-10-07T15:00:00Z")),
            eq(Instant.parse("2026-10-08T15:00:00Z")), any());
    }

    @Test void emptyHistoryAndFinalSuccessAreSeparate() {
        when(runs.findFirstByExecutionTypeOrderByStartedAtDescIdDesc(any())).thenReturn(Optional.empty());
        when(runs.findFirstByExecutionTypeAndStatusOrderByFinishedAtDescIdDesc(any(), any())).thenReturn(Optional.empty());
        assertThat(service.monitoringSummary().latestRun()).isNull();
    }
    @Test void failedUnknownCandidateRemainsUnknownAndUsesOnlyFixedErrorSummary() {
        var run = PaymentDiscrepancyAuditRun.start("node", clock.instant());
        run.finish(clock.instant(), new PaymentDiscrepancyAuditRunSummary(
            PaymentDiscrepancyAuditRunStatus.FAILED,null,0,0,0,0,0,1,
            PaymentDiscrepancyAuditRunErrorCode.CANDIDATE_FETCH_FAILED));
        org.springframework.test.util.ReflectionTestUtils.setField(run,"errorSummary","secret API response");
        when(runs.searchForAdmin(any(),isNull(),isNull(),isNull(),any())).thenReturn(new PageImpl<>(List.of(run)));
        var item=service.search(new AdminPaymentDiscrepancyAuditRunSearchForm(),0,20).getContent().getFirst();
        assertThat(item.candidateCount()).isNull(); assertThat(item.unprocessedCount()).isNull();
        assertThat(item.successCount()).isZero();
        assertThat(item.errorSummary()).isEqualTo(PaymentDiscrepancyAuditRunErrorCode.CANDIDATE_FETCH_FAILED.summary())
            .doesNotContain("secret");
    }
    @Test void allSkippedIsSuccessfulBatchWithoutSuccessfulPaymentChecks() {
        var run = PaymentDiscrepancyAuditRun.start("node",clock.instant());
        run.finish(clock.instant(),new PaymentDiscrepancyAuditRunSummary(
            PaymentDiscrepancyAuditRunStatus.SUCCESS,2,0,0,0,2,0,1,null));
        when(runs.searchForAdmin(any(),isNull(),isNull(),isNull(),any())).thenReturn(new PageImpl<>(List.of(run)));
        var item=service.search(new AdminPaymentDiscrepancyAuditRunSearchForm(),0,20).getContent().getFirst();
        assertThat(item.status()).isEqualTo(PaymentDiscrepancyAuditRunStatus.SUCCESS);
        assertThat(item.successCount()).isZero(); assertThat(item.skippedCount()).isEqualTo(2);
        assertThat(item.unprocessedCount()).isZero();
    }

}
