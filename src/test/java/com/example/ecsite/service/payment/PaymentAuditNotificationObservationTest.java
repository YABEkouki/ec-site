package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.*;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;
import com.example.ecsite.dto.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.AdminPaymentDiscrepancyAuditRunService;

class PaymentAuditNotificationObservationTest {
    static final Instant NOW = Instant.parse("2026-10-09T01:00:00Z");
    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    final AdminPaymentDiscrepancyAuditRunService monitoring = mock(AdminPaymentDiscrepancyAuditRunService.class);
    final PaymentAuditNotificationStateRepository states = mock(PaymentAuditNotificationStateRepository.class);
    final PaymentAuditNotificationRepository notifications = mock(PaymentAuditNotificationRepository.class);
    final PaymentAuditNotificationItemRepository items = mock(PaymentAuditNotificationItemRepository.class);
    final List<PaymentAuditNotificationState> rows = new ArrayList<>();
    final List<PaymentAuditNotification> saved = new ArrayList<>();
    final List<PaymentAuditNotificationItem> savedItems = new ArrayList<>();
    PaymentAuditNotificationObservationTransaction tx;

    static PaymentDiscrepancyAuditNotificationProperties properties(boolean enabled, String... recipients) {
        return new PaymentDiscrepancyAuditNotificationProperties(enabled, List.of(recipients), Duration.ofMinutes(1),
            Duration.ofMinutes(15), Duration.ofMinutes(5), 3, Duration.ofMinutes(3),
            new PaymentDiscrepancyAuditNotificationProperties.Smtp(Duration.ofSeconds(5),Duration.ofSeconds(10),Duration.ofSeconds(10),Duration.ofMinutes(2)));
    }
    static AdminPaymentDiscrepancyAuditMonitoringSummary summary(Instant now, PaymentDiscrepancyAuditRunStatus status,
            AdminPaymentDiscrepancyAuditWarning... warnings) {
        var latest = status == null ? null : new AdminPaymentDiscrepancyAuditRunListItem(1L,
            LocalDateTime.ofInstant(now.minusSeconds(30), TOKYO), status == PaymentDiscrepancyAuditRunStatus.RUNNING ? null : LocalDateTime.ofInstant(now,TOKYO),
            status,"test",null,null,null,null,null,null,null,null,null,null);
        return new AdminPaymentDiscrepancyAuditMonitoringSummary(latest,null,0,0,true,LocalDateTime.ofInstant(now,TOKYO),Duration.ofHours(24),List.of(warnings));
    }
    static AdminPaymentDiscrepancyAuditWarning warning(PaymentAuditNotificationWarningType type, Instant related, long count) {
        return new AdminPaymentDiscrepancyAuditWarning(AdminPaymentDiscrepancyAuditWarning.Type.valueOf(type.name()),
            "固定説明", related == null ? null : LocalDateTime.ofInstant(related,TOKYO),count,null);
    }
    @BeforeEach void setup() {
        for (var type : PaymentAuditNotificationWarningType.values()) {
            var row = new PaymentAuditNotificationState(); row.setWarningType(type); rows.add(row);
        }
        when(states.findAllForObservation()).thenReturn(rows);
        when(notifications.findAllCancelableForObservation()).thenAnswer(inv -> saved.stream()
            .filter(n -> n.getStatus()==PaymentAuditNotificationStatus.PENDING || n.getStatus()==PaymentAuditNotificationStatus.RETRY_WAIT).toList());
        AtomicLong ids = new AtomicLong();
        when(notifications.save(any())).thenAnswer(inv -> {
            PaymentAuditNotification n = inv.getArgument(0); ReflectionTestUtils.setField(n,"id",ids.incrementAndGet()); saved.add(n); return n;
        });
        when(items.save(any())).thenAnswer(inv -> {PaymentAuditNotificationItem i=inv.getArgument(0); savedItems.add(i); return i;});
        when(items.findByStateWarningTypeAndEpisodeNo(any(),anyLong())).thenAnswer(inv -> savedItems.stream()
            .filter(i -> i.getState().getWarningType()==inv.getArgument(0) && i.getEpisodeNo()==(long)inv.getArgument(1)).findFirst());
        when(items.findByNotificationIdOrderByIdAsc(anyLong())).thenAnswer(inv -> savedItems.stream()
            .filter(i -> i.getNotification().getId().equals(inv.getArgument(0))).toList());
        tx = transaction(properties(true,"admin@example.com"));
    }
    PaymentAuditNotificationObservationTransaction transaction(PaymentDiscrepancyAuditNotificationProperties p) {
        return new PaymentAuditNotificationObservationTransaction(p,monitoring,states,notifications,items,"no-reply@ec-site.local","http://localhost:8080/");
    }
    PaymentAuditNotificationState state(PaymentAuditNotificationWarningType type) {
        return rows.stream().filter(s -> s.getWarningType()==type).findFirst().orElseThrow();
    }
    void evaluate(Instant now, PaymentDiscrepancyAuditRunStatus status, AdminPaymentDiscrepancyAuditWarning... warnings) {
        when(monitoring.monitoringSummary()).thenReturn(summary(now,status,warnings)); tx.observe();
    }
    @Test void initialNormalDoesNotCreateNotifications() {
        evaluate(NOW,PaymentDiscrepancyAuditRunStatus.SUCCESS);
        assertThat(rows).allSatisfy(s -> {assertThat(s.isActive()).isFalse();assertThat(s.getEpisodeNo()).isZero();assertThat(s.getLastEvaluatedAt()).isEqualTo(NOW);});
        assertThat(saved).isEmpty();
    }
    @ParameterizedTest @EnumSource(PaymentAuditNotificationWarningType.class)
    void everyWarningMaintainsEpisodeAndObservationThenResolvesAndRecurs(PaymentAuditNotificationWarningType type) {
        var w=warning(type,NOW.minusSeconds(60),1);
        evaluate(NOW,PaymentDiscrepancyAuditRunStatus.FAILED,w);
        evaluate(NOW.plusSeconds(900),PaymentDiscrepancyAuditRunStatus.FAILED,warning(type,NOW.minusSeconds(120),8));
        assertThat(state(type).getEpisodeNo()).isEqualTo(1);
        assertThat(state(type).getFirstObservedAt()).isEqualTo(NOW);
        assertThat(saved).hasSize(1);
        assertThat(savedItems.getFirst().getFirstObservedAt()).isEqualTo(NOW);
        assertThat(savedItems.getFirst().getRelatedAt()).isNotEqualTo(NOW);
        evaluate(NOW.plusSeconds(901),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        assertThat(state(type).isActive()).isFalse();
        assertThat(state(type).getResolvedAt()).isEqualTo(NOW.plusSeconds(901));
        assertThat(saved.getFirst().getStatus()).isEqualTo(PaymentAuditNotificationStatus.CANCELLED);
        assertThat(savedItems.getFirst().getItemStatus()).isEqualTo(PaymentAuditNotificationItemStatus.REMOVED_RESOLVED);
        evaluate(NOW.plusSeconds(902),PaymentDiscrepancyAuditRunStatus.FAILED,w);
        evaluate(NOW.plusSeconds(1802),PaymentDiscrepancyAuditRunStatus.FAILED,w);
        assertThat(state(type).getEpisodeNo()).isEqualTo(2);
        assertThat(saved).hasSize(2);
    }
    @ParameterizedTest @ValueSource(longs={899,900,901})
    void noHistoryGraceIsInclusiveAtFifteenMinutes(long elapsed) {
        var w=warning(PaymentAuditNotificationWarningType.NO_HISTORY,null,0);
        evaluate(NOW,null,w);
        assertThat(saved).isEmpty();
        tx=transaction(properties(true,"admin@example.com")); // restart equivalent, persistent state retained
        evaluate(NOW.plusSeconds(elapsed),null,w);
        assertThat(saved).hasSize(elapsed>=900?1:0);
        assertThat(state(PaymentAuditNotificationWarningType.NO_HISTORY).getFirstObservedAt()).isEqualTo(NOW);
    }
    @Test void noHistoryResolvesDuringGraceWithoutNotification() {
        evaluate(NOW,null,warning(PaymentAuditNotificationWarningType.NO_HISTORY,null,0));
        evaluate(NOW.plusSeconds(899),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        assertThat(saved).isEmpty();assertThat(state(PaymentAuditNotificationWarningType.NO_HISTORY).isActive()).isFalse();
    }
    @Test void aggregatesOnlyNewWarningsInFeature114OrderAndCancelsPartially() {
        var failure=warning(PaymentAuditNotificationWarningType.LATEST_FAILURE,NOW,1);
        var running=warning(PaymentAuditNotificationWarningType.LONG_RUNNING,NOW,2);
        var unhandled=warning(PaymentAuditNotificationWarningType.LONG_UNHANDLED,NOW,3);
        evaluate(NOW,PaymentDiscrepancyAuditRunStatus.FAILED,failure,running);
        assertThat(saved).hasSize(1);
        assertThat(savedItems).extracting(i->i.getState().getWarningType()).containsExactly(PaymentAuditNotificationWarningType.LATEST_FAILURE,PaymentAuditNotificationWarningType.LONG_RUNNING);
        evaluate(NOW.plusSeconds(1),PaymentDiscrepancyAuditRunStatus.FAILED,failure,unhandled);
        assertThat(saved).hasSize(2);
        assertThat(savedItems).hasSize(3);
        assertThat(savedItems.get(1).getItemStatus()).isEqualTo(PaymentAuditNotificationItemStatus.REMOVED_RESOLVED);
        assertThat(saved.getFirst().getStatus()).isEqualTo(PaymentAuditNotificationStatus.PENDING);
        assertThat(saved.getFirst().getRecipients()).containsExactly("admin@example.com");
        assertThat(saved.getFirst().getRetryDelayMs()).isEqualTo(300000);
        assertThat(saved.getFirst().getMaxAttempts()).isEqualTo(3);
        assertThat(saved.getFirst().getAdminUrl()).isEqualTo("http://localhost:8080/admin/payment-discrepancy-audits");
        assertThat(saved.getFirst().getRecipientSetHash()).matches("[0-9a-f]{64}");
    }
    @Test void latestFailureHoldsThroughRunningMissingHistoryAndPartialFailure() {
        var failure=warning(PaymentAuditNotificationWarningType.LATEST_FAILURE,NOW,1);
        evaluate(NOW,PaymentDiscrepancyAuditRunStatus.FAILED,failure);
        evaluate(NOW.plusSeconds(1),PaymentDiscrepancyAuditRunStatus.RUNNING);
        evaluate(NOW.plusSeconds(2),null);
        evaluate(NOW.plusSeconds(3),PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE,failure);
        assertThat(state(PaymentAuditNotificationWarningType.LATEST_FAILURE).isActive()).isTrue();
        assertThat(state(PaymentAuditNotificationWarningType.LATEST_FAILURE).getEpisodeNo()).isEqualTo(1);
        assertThat(saved).hasSize(1);
        evaluate(NOW.plusSeconds(4),PaymentDiscrepancyAuditRunStatus.SUCCESS);
        assertThat(state(PaymentAuditNotificationWarningType.LATEST_FAILURE).isActive()).isFalse();
    }
    @Test void recipientChangeDoesNotRecreateCurrentEpisode() {
        var w=warning(PaymentAuditNotificationWarningType.LONG_RUNNING,NOW,1);
        evaluate(NOW,PaymentDiscrepancyAuditRunStatus.RUNNING,w);
        tx=transaction(properties(true,"new@example.com"));
        evaluate(NOW.plusSeconds(1),PaymentDiscrepancyAuditRunStatus.RUNNING,w);
        assertThat(saved).hasSize(1);assertThat(saved.getFirst().getRecipients()).containsExactly("admin@example.com");
    }
    @Test void disabledDoesNotEvaluateOrAccessDatabase() {
        transaction(properties(false)).observe();verifyNoInteractions(monitoring,states,notifications,items);
        var boundary=mock(PaymentAuditNotificationObservationTransaction.class);
        new PaymentAuditNotificationObservationService(properties(false),boundary).observe();verifyNoInteractions(boundary);
    }
    @ParameterizedTest @ValueSource(strings={"40001","40P01"})
    void retriesOnlyWholeObservationTransactions(String sqlState) {
        var boundary=mock(PaymentAuditNotificationObservationTransaction.class);
        doThrow(new RuntimeException(new SQLException("safe",sqlState))).doNothing().when(boundary).observe();
        new PaymentAuditNotificationObservationService(properties(true,"admin@example.com"),boundary).observe();
        verify(boundary,times(2)).observe();
    }
    @Test void stopsAfterThreeConflictsAndDoesNotRetryOtherErrors() {
        var boundary=mock(PaymentAuditNotificationObservationTransaction.class);
        var failure=new RuntimeException(new SQLException("safe","40001"));
        doThrow(failure).when(boundary).observe();
        assertThatThrownBy(()->new PaymentAuditNotificationObservationService(properties(true,"admin@example.com"),boundary).observe()).isSameAs(failure);
        verify(boundary,times(3)).observe();
        reset(boundary);doThrow(new IllegalStateException("evaluation failed")).when(boundary).observe();
        assertThatThrownBy(()->new PaymentAuditNotificationObservationService(properties(true,"admin@example.com"),boundary).observe()).isInstanceOf(IllegalStateException.class);
        verify(boundary).observe();
    }
}
