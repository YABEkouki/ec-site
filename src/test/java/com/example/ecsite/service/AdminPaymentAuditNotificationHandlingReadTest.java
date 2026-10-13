package com.example.ecsite.service;

import java.time.*;
import java.util.*;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.example.ecsite.dto.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.form.AdminPaymentAuditNotificationSearchForm;
import com.example.ecsite.repository.*;
import com.example.ecsite.repository.projection.*;
import org.springframework.data.domain.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AdminPaymentAuditNotificationHandlingReadTest {
    PaymentAuditNotificationRepository notifications=mock(PaymentAuditNotificationRepository.class);
    PaymentAuditNotificationItemRepository items=mock(PaymentAuditNotificationItemRepository.class);
    PaymentAuditNotificationAttemptRepository attempts=mock(PaymentAuditNotificationAttemptRepository.class);
    PaymentAuditNotificationHandlingRepository handlings=mock(PaymentAuditNotificationHandlingRepository.class);
    PaymentAuditNotificationHandlingHistoryRepository histories=mock(PaymentAuditNotificationHandlingHistoryRepository.class);
    PaymentAuditNotificationHandlingHistoryUnknownRepository unknowns=mock(PaymentAuditNotificationHandlingHistoryUnknownRepository.class);
    Validator validator=mock(Validator.class);
    PaymentAuditNotificationHandlingToken tokens=new PaymentAuditNotificationHandlingToken("",Clock.systemUTC());
    AdminPaymentAuditNotificationService service() {return new AdminPaymentAuditNotificationService(notifications,items,attempts,validator,handlings,histories,unknowns,tokens);}
    AdminPaymentAuditNotificationProjection detail(PaymentAuditNotificationStatus status,boolean uncertain) {
        var n=mock(AdminPaymentAuditNotificationProjection.class);
        when(n.getId()).thenReturn(1L);when(n.getStatus()).thenReturn(status);when(n.getDeliveryUncertain()).thenReturn(uncertain);
        when(n.getAttemptCount()).thenReturn(2);when(n.getMaxAttempts()).thenReturn(3);when(n.getRecipientCount()).thenReturn(2);
        when(notifications.findSummaryForAdmin(1L)).thenReturn(Optional.of(n));
        when(handlings.findById(1L)).thenReturn(Optional.empty());return n;
    }
    @ParameterizedTest @CsvSource({"EXHAUSTED,false,true","SENT,true,true","CANCELLED,true,true","SENT,false,false","PENDING,false,false"})
    void eligibilityAndMissingHandling(PaymentAuditNotificationStatus status,boolean uncertain,boolean actionable) {
        detail(status,uncertain);
        var result=service().findById(1L,2L).handling();
        assertThat(result.actionable()).isEqualTo(actionable);
        assertThat(result.handlingStatus()).isEqualTo(PaymentAuditNotificationHandlingStatus.UNREVIEWED);
        assertThat(result.snapshotToken()).isNull();assertThat(result.handlingOperationAvailable()).isFalse();
    }
    @Test void unresolvedUnknownMembershipAndActorHistory() {
        detail(PaymentAuditNotificationStatus.SENT,true);
        var h=new PaymentAuditNotificationHandling();h.setHandlingStatus(PaymentAuditNotificationHandlingStatus.RESOLVED);h.setLastResolvedHistoryId(10L);
        when(handlings.findById(1L)).thenReturn(Optional.of(h));
        var history=new PaymentAuditNotificationHandlingHistory();history.setId(10L);history.setActorUserId(2L);history.setActorUsername("admin");history.setComment("確認済み");
        when(histories.findByNotificationIdOrderByChangedAtAscIdAsc(1L)).thenReturn(List.of(history));
        var confirmed=new PaymentAuditNotificationHandlingHistoryUnknown();confirmed.setAttemptNo(1);
        when(unknowns.findByHistoryIdOrderByAttemptNoAsc(10L)).thenReturn(List.of(confirmed));
        var a=mock(AdminPaymentAuditNotificationAttemptProjection.class);when(a.getAttemptNo()).thenReturn(1);when(a.getResult()).thenReturn(PaymentAuditNotificationAttemptResult.UNKNOWN);
        var b=mock(AdminPaymentAuditNotificationAttemptProjection.class);when(b.getAttemptNo()).thenReturn(2);when(b.getResult()).thenReturn(PaymentAuditNotificationAttemptResult.IN_PROGRESS);
        when(attempts.findForAdminByNotificationId(1L)).thenReturn(List.of(a,b));
        assertThat(service().findById(1L).handling().requiresRecheck()).isFalse();
        when(b.getResult()).thenReturn(PaymentAuditNotificationAttemptResult.UNKNOWN);
        var result=service().findById(1L).handling();
        assertThat(result.requiresRecheck()).isTrue();assertThat(result.currentUnknowns()).containsExactly(1,2);
        assertThat(result.confirmedUnknowns()).containsExactly(1);assertThat(result.lastActorUsername()).isEqualTo("admin");
        assertThat(result.histories().getFirst().comment()).isEqualTo("確認済み");
    }
    @Test void signedTokenMatchesReadSnapshotAndToStringRedactsIt() {
        detail(PaymentAuditNotificationStatus.EXHAUSTED,false);
        tokens=new PaymentAuditNotificationHandlingToken(Base64.getEncoder().encodeToString(new byte[32]),Clock.systemUTC());
        var result=service().findById(1L,2L).handling();
        assertThat(tokens.verify(result.snapshotToken(),1,2).handlingExists()).isFalse();
        assertThat(result.toString()).doesNotContain(result.snapshotToken());
        assertThat(service().findById(1L).handling().snapshotToken()).isNull();
    }
    @Test void forwardsAndFiltersAndKeepsNotificationPaging() {
        var form=new AdminPaymentAuditNotificationSearchForm();form.setHandlingStatus(PaymentAuditNotificationHandlingStatus.RESOLVED);
        form.setActionableOnly(true);form.setRequiresRecheck(true);form.setNotificationId(1L);form.setStatus(PaymentAuditNotificationStatus.SENT);form.setWarningType(PaymentAuditNotificationWarningType.LATEST_FAILURE);
        form.setFrom(LocalDate.of(2026,10,9));form.setTo(LocalDate.of(2026,10,9));
        when(validator.validate(form)).thenReturn(Set.of());
        Instant from=Instant.parse("2026-10-08T15:00:00Z"),to=Instant.parse("2026-10-09T15:00:00Z");
        when(notifications.searchForAdmin(eq(1L),eq(PaymentAuditNotificationStatus.SENT),eq(PaymentAuditNotificationWarningType.LATEST_FAILURE),eq(from),eq(to),eq(PaymentAuditNotificationHandlingStatus.RESOLVED),eq(true),eq(true),any()))
            .thenReturn(new PageImpl<>(List.of(),PageRequest.of(0,50),0));
        var result=service().search(form,-1,50);assertThat(result.getTotalElements()).isZero();assertThat(result.getSize()).isEqualTo(50);
        verify(notifications).searchForAdmin(eq(1L),eq(PaymentAuditNotificationStatus.SENT),eq(PaymentAuditNotificationWarningType.LATEST_FAILURE),eq(from),eq(to),eq(PaymentAuditNotificationHandlingStatus.RESOLVED),eq(true),eq(true),eq(PageRequest.of(0,50)));
        verifyNoInteractions(histories,handlings,unknowns,items);
    }
}
