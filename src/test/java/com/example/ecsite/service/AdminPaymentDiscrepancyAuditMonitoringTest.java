package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.ecsite.config.*;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditWarning.Type;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.repository.projection.PaymentDiscrepancyAuditRunningSummaryProjection;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditRunSummary;

class AdminPaymentDiscrepancyAuditMonitoringTest {
    final Instant now = Instant.parse("2026-10-08T00:00:00Z");
    final Clock clock = mock(Clock.class);
    final PaymentDiscrepancyAuditRunRepository runs = mock(PaymentDiscrepancyAuditRunRepository.class);
    final PaymentDiscrepancyRepository discrepancies = mock(PaymentDiscrepancyRepository.class);
    final PaymentDiscrepancyAuditMonitoringProperties settings = new PaymentDiscrepancyAuditMonitoringProperties(
        Duration.ofMinutes(15),Duration.ofMinutes(10),3,Duration.ofHours(24));
    @BeforeEach void defaults() {
        when(clock.instant()).thenReturn(now);
        when(runs.findFirstByExecutionTypeOrderByStartedAtDescIdDesc(any())).thenReturn(Optional.empty());
        when(runs.findFirstByExecutionTypeAndStatusOrderByFinishedAtDescIdDesc(any(),any())).thenReturn(Optional.empty());
        when(runs.findRecentFinishedForMonitoring(any(),any())).thenReturn(List.of());
        var emptyRunning = running(0,null);
        when(runs.summarizeLongRunning(any(),any())).thenReturn(emptyRunning);
    }
    AdminPaymentDiscrepancyAuditRunService service(boolean enabled) {
        return new AdminPaymentDiscrepancyAuditRunService(runs,discrepancies,clock,
            new PaymentDiscrepancyAuditProperties(enabled,Duration.ofMinutes(5),100), settings);
    }
    @Test void noHistoryWarnsOnlyWhenEnabledAndUsesClockOnce() {
        assertThat(service(true).monitoringSummary().warnings()).extracting(w->w.type()).containsExactly(Type.NO_HISTORY);
        verify(clock,times(1)).instant();
        var disabled=service(false).monitoringSummary();
        assertThat(disabled.schedulerEnabled()).isFalse();assertThat(disabled.warnings()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","PARTIAL_FAILURE"})
    void latestFailureExplainsWholeOrPartialAndCarriesSafeErrorCode(String status) {
        var latest=finished(PaymentDiscrepancyAuditRunStatus.valueOf(status),now.minusSeconds(30),now);
        latest(latest);
        var warning=service(true).monitoringSummary().warnings().getFirst();
        assertThat(warning.type()).isEqualTo(Type.LATEST_FAILURE);
        assertThat(warning.description()).contains(status.equals("FAILED")?"監査処理が失敗":"一部の監査処理が失敗");
        assertThat(warning.errorCode()).isEqualTo(latest.getErrorCode());
        assertThat(warning.relatedAt()).isEqualTo(LocalDateTime.of(2026,10,8,8,59,30));
    }
    @Test void recentSuccessWithoutOtherConditionsHasNoWarnings() {
        latest(finished(PaymentDiscrepancyAuditRunStatus.SUCCESS,now.minusSeconds(30),now));
        assertThat(service(true).monitoringSummary().warnings()).isEmpty();
    }
    @Test void runningQueryUsesInclusive15MinuteCutoffAndOldestTimeNotLatest() {
        latest(finished(PaymentDiscrepancyAuditRunStatus.SUCCESS,now.minusSeconds(10),now));
        var aggregate = running(2,now.minusSeconds(3600));
        when(runs.summarizeLongRunning(any(),any())).thenReturn(aggregate);
        var warning=service(true).monitoringSummary().warnings().getFirst();
        assertThat(warning.type()).isEqualTo(Type.LONG_RUNNING);assertThat(warning.count()).isEqualTo(2);
        assertThat(warning.relatedAt()).isEqualTo(LocalDateTime.of(2026,10,8,8,0));
        assertThat(warning.description()).doesNotContain("確実に停止");
        verify(runs).summarizeLongRunning(PaymentDiscrepancyAuditExecutionType.SCHEDULED,now.minusSeconds(900));
    }
    @ParameterizedTest @CsvSource({"899,false","900,false","901,true"})
    void delayUsesFinishTimeWithStrictBoundary(long elapsed,boolean expected) {
        latest(finished(PaymentDiscrepancyAuditRunStatus.SUCCESS,now.minusSeconds(3600),now.minusSeconds(elapsed)));
        assertThat(service(true).monitoringSummary().warnings().stream().anyMatch(w->w.type()==Type.DELAYED)).isEqualTo(expected);
    }
    @Test void disabledSchedulerSuppressesDelay() {
        latest(finished(PaymentDiscrepancyAuditRunStatus.SUCCESS,now.minusSeconds(3601),now.minusSeconds(3600)));
        assertThat(service(false).monitoringSummary().warnings()).isEmpty();
    }
    @Test void latestRunningSuppressesDelayEvenWhenOldAndFutureTimeDoesNotDelay() {
        latest(PaymentDiscrepancyAuditRun.start("node",now.minusSeconds(3600)));
        assertThat(service(true).monitoringSummary().warnings()).noneMatch(w->w.type()==Type.DELAYED);
        latest(finished(PaymentDiscrepancyAuditRunStatus.SUCCESS,now.plusSeconds(30),now.plusSeconds(60)));
        assertThat(service(true).monitoringSummary().warnings()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"FAILED,FAILED,FAILED","FAILED,FAILED","FAILED,PARTIAL_FAILURE,FAILED","FAILED,SUCCESS,FAILED"})
    void consecutiveFailuresNeedThreeFinishedFailedRuns(String statuses) {
        latest(finished(PaymentDiscrepancyAuditRunStatus.SUCCESS,now.minusSeconds(1),now));
        var recent=Arrays.stream(statuses.split(",")).map(s->finished(PaymentDiscrepancyAuditRunStatus.valueOf(s),now.minusSeconds(1),now)).toList();
        when(runs.findRecentFinishedForMonitoring(any(),any())).thenReturn(recent);
        boolean expected=statuses.equals("FAILED,FAILED,FAILED");
        assertThat(service(true).monitoringSummary().warnings().stream().anyMatch(w->w.type()==Type.CONSECUTIVE_FAILURES)).isEqualTo(expected);
        verify(runs).findRecentFinishedForMonitoring(eq(PaymentDiscrepancyAuditExecutionType.SCHEDULED),argThat(p->p.getPageSize()==3));
    }
    @Test void runningLatestDoesNotPreventFinishedFailureStreak() {
        latest(PaymentDiscrepancyAuditRun.start("node",now));
        when(runs.findRecentFinishedForMonitoring(any(),any())).thenReturn(Collections.nCopies(3,
            finished(PaymentDiscrepancyAuditRunStatus.FAILED,now.minusSeconds(10),now.minusSeconds(1))));
        assertThat(service(true).monitoringSummary().warnings()).extracting(w->w.type()).containsExactly(Type.CONSECUTIVE_FAILURES);
    }
    @Test void longUnhandledReusesFixedNowAndMultipleWarningsHaveStablePriority() {
        var failed=finished(PaymentDiscrepancyAuditRunStatus.FAILED,now.minusSeconds(3601),now.minusSeconds(3600));latest(failed);
        when(runs.findRecentFinishedForMonitoring(any(),any())).thenReturn(Collections.nCopies(3,failed));
        var aggregate = running(2,now.minusSeconds(7200));
        when(runs.summarizeLongRunning(any(),any())).thenReturn(aggregate);
        when(discrepancies.countLongUnhandled(any(),any(),any())).thenReturn(1L);
        var summary=service(true).monitoringSummary();
        assertThat(summary.warnings()).extracting(w->w.type()).containsExactly(Type.CONSECUTIVE_FAILURES,
            Type.LATEST_FAILURE,Type.LONG_RUNNING,Type.DELAYED,Type.LONG_UNHANDLED);
        verify(discrepancies).countLongUnhandled(PaymentDiscrepancyRecordStatus.OPEN,PaymentDiscrepancyHandlingStatus.COMPLETED,
            LocalDateTime.of(2026,10,7,9,0));verify(clock,times(1)).instant();
    }
    @Test void disabledSchedulerRetainsHistoricalFailureRunningAndUnhandledWarnings() {
        var failed=finished(PaymentDiscrepancyAuditRunStatus.FAILED,now.minusSeconds(3601),now.minusSeconds(3600));
        latest(failed);
        when(runs.findRecentFinishedForMonitoring(any(),any())).thenReturn(Collections.nCopies(3,failed));
        var aggregate=running(1,now.minusSeconds(7200));
        when(runs.summarizeLongRunning(any(),any())).thenReturn(aggregate);
        when(discrepancies.countLongUnhandled(any(),any(),any())).thenReturn(1L);
        assertThat(service(false).monitoringSummary().warnings()).extracting(w->w.type()).containsExactly(
            Type.CONSECUTIVE_FAILURES,Type.LATEST_FAILURE,Type.LONG_RUNNING,Type.LONG_UNHANDLED);
    }
    @Test void configuredThresholdsAreUsedInQueriesAndFailureStreak() {
        latest(PaymentDiscrepancyAuditRun.start("node",now));
        var failed=finished(PaymentDiscrepancyAuditRunStatus.FAILED,now.minusSeconds(20),now.minusSeconds(10));
        when(runs.findRecentFinishedForMonitoring(any(),any())).thenReturn(Collections.nCopies(2,failed));
        var custom=new PaymentDiscrepancyAuditMonitoringProperties(Duration.ofMinutes(30),Duration.ZERO,2,Duration.ofHours(48));
        var service=new AdminPaymentDiscrepancyAuditRunService(runs,discrepancies,clock,
            new PaymentDiscrepancyAuditProperties(true,Duration.ofMinutes(5),100),custom);
        assertThat(service.monitoringSummary().warnings()).extracting(w->w.type()).containsExactly(Type.CONSECUTIVE_FAILURES);
        verify(runs).summarizeLongRunning(PaymentDiscrepancyAuditExecutionType.SCHEDULED,now.minusSeconds(1800));
        verify(runs).findRecentFinishedForMonitoring(eq(PaymentDiscrepancyAuditExecutionType.SCHEDULED),argThat(p->p.getPageSize()==2));
        verify(discrepancies).countLongUnhandled(PaymentDiscrepancyRecordStatus.OPEN,PaymentDiscrepancyHandlingStatus.COMPLETED,
            LocalDateTime.of(2026,10,6,9,0));
    }
    void latest(PaymentDiscrepancyAuditRun run) { when(runs.findFirstByExecutionTypeOrderByStartedAtDescIdDesc(any())).thenReturn(Optional.of(run)); }
    PaymentDiscrepancyAuditRunningSummaryProjection running(long count,Instant oldest) {
        var p=mock(PaymentDiscrepancyAuditRunningSummaryProjection.class);when(p.getCount()).thenReturn(count);when(p.getOldestStartedAt()).thenReturn(oldest);return p;
    }
    PaymentDiscrepancyAuditRun finished(PaymentDiscrepancyAuditRunStatus status,Instant start,Instant finish) {
        var run=PaymentDiscrepancyAuditRun.start("node",start);
        boolean success=status==PaymentDiscrepancyAuditRunStatus.SUCCESS;
        boolean partial=status==PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE;
        run.finish(finish,new PaymentDiscrepancyAuditRunSummary(status,partial?2:1,success||partial?1:0,0,0,0,success?0:1,1,
            success?null:partial?PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE:PaymentDiscrepancyAuditRunErrorCode.ALL_ITEMS_FAILED));return run;
    }
}
