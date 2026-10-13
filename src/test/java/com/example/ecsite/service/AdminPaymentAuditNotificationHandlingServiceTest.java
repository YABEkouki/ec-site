package com.example.ecsite.service;

import java.util.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AdminPaymentAuditNotificationHandlingServiceTest {
    @Mock PaymentAuditNotificationHandlingToken tokens;
    @Mock PaymentAuditNotificationHandlingRepository handlings;
    @Mock PaymentAuditNotificationHandlingHistoryRepository histories;
    @Mock PaymentAuditNotificationHandlingHistoryUnknownRepository unknowns;
    @Mock EntityManager entities;
    @InjectMocks AdminPaymentAuditNotificationHandlingService service;
    void setup(PaymentAuditNotificationHandling h,List<Integer> numbers) {
        when(tokens.verify("token",1,2)).thenReturn(new PaymentAuditNotificationHandlingToken.Snapshot(h!=null,0,numbers==null?List.of():numbers));
        var n=new PaymentAuditNotification(); n.setStatus(PaymentAuditNotificationStatus.EXHAUSTED);
        when(handlings.lockNotification(1)).thenReturn(Optional.of(n));
        when(handlings.findById(1L)).thenReturn(Optional.ofNullable(h));
        if(numbers!=null) when(handlings.findUnknownAttemptNumbers(1)).thenReturn(numbers);
    }
    void history() {
        when(histories.saveAndFlush(any())).thenAnswer(i->{var h=(PaymentAuditNotificationHandlingHistory)i.getArgument(0);h.setId(10L);return h;});
    }
    @Test void firstResolutionPersistsSnapshotAndHandlingAfterHistory() {
        setup(null,List.of(1,3)); history();
        assertThat(service.changeStatus(1,2,"admin","token",PaymentAuditNotificationHandlingStatus.RESOLVED,"確認済み")).isTrue();
        var order=inOrder(histories,entities); order.verify(histories).saveAndFlush(any());
        order.verify(entities,times(2)).persist(isA(PaymentAuditNotificationHandlingHistoryUnknown.class));
        var captor=ArgumentCaptor.forClass(PaymentAuditNotificationHandling.class);
        order.verify(entities).persist(captor.capture()); order.verify(entities).flush();
        assertThat(captor.getValue().getLastResolvedHistoryId()).isEqualTo(10L);
    }
    @Test void sameStateIsCompleteNoOp() {
        setup(null,List.of()); assertThat(service.changeStatus(1,2,"admin","token",PaymentAuditNotificationHandlingStatus.UNREVIEWED,null)).isFalse();
        verifyNoInteractions(histories,entities,unknowns);
    }
    @Test void reopeningKeepsLastResolvedHistory() {
        var h=new PaymentAuditNotificationHandling();h.setHandlingStatus(PaymentAuditNotificationHandlingStatus.RESOLVED);h.setLastResolvedHistoryId(7L);
        setup(h,List.of(1));history();service.changeStatus(1,2,"admin","token",PaymentAuditNotificationHandlingStatus.IN_PROGRESS,"再確認");
        assertThat(h.getLastResolvedHistoryId()).isEqualTo(7L); assertThat(h.getHandlingStatus()).isEqualTo(PaymentAuditNotificationHandlingStatus.IN_PROGRESS);
        verify(entities,never()).persist(any());
    }
    @Test void resolutionRequiresComment() {
        setup(null,List.of());
        assertThatThrownBy(()->service.changeStatus(1,2,"admin","token",PaymentAuditNotificationHandlingStatus.RESOLVED,"\u200b")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(histories,entities);
    }
    @Test void changedUnknownRejectsUpdate() {
        setup(null,List.of(1));when(handlings.findUnknownAttemptNumbers(1)).thenReturn(List.of(1,2));
        assertThatThrownBy(()->service.changeStatus(1,2,"admin","token",PaymentAuditNotificationHandlingStatus.RESOLVED,"確認")).isInstanceOf(PaymentAuditNotificationHandlingConflictException.class);
        verifyNoInteractions(histories,entities);
    }
    @Test void concurrentFirstInsertRejectsUpdate() {
        setup(null,null);when(handlings.findById(1L)).thenReturn(Optional.of(new PaymentAuditNotificationHandling()));
        assertThatThrownBy(()->service.changeStatus(1,2,"admin","token",PaymentAuditNotificationHandlingStatus.IN_PROGRESS,null)).isInstanceOf(PaymentAuditNotificationHandlingConflictException.class);
        verifyNoInteractions(histories,entities);
    }
    @Test void staleVersionRejectsUpdate() {
        setup(null,null);
        when(tokens.verify("token",1,2)).thenReturn(new PaymentAuditNotificationHandlingToken.Snapshot(true,1,List.of()));
        when(handlings.findById(1L)).thenReturn(Optional.of(new PaymentAuditNotificationHandling()));
        assertThatThrownBy(()->service.changeStatus(1,2,"admin","token",PaymentAuditNotificationHandlingStatus.IN_PROGRESS,null)).isInstanceOf(PaymentAuditNotificationHandlingConflictException.class);
        verifyNoInteractions(histories,entities);
    }
    @Test void historyFailurePreventsHandlingMutation() {
        setup(null,List.of());when(histories.saveAndFlush(any())).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(()->service.changeStatus(1,2,"admin","token",PaymentAuditNotificationHandlingStatus.IN_PROGRESS,null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(entities);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"UNREVIEWED,IN_PROGRESS","UNREVIEWED,RESOLVED","IN_PROGRESS,UNREVIEWED","IN_PROGRESS,RESOLVED","RESOLVED,UNREVIEWED","RESOLVED,IN_PROGRESS"})
    void allAllowedTransitions(PaymentAuditNotificationHandlingStatus from,PaymentAuditNotificationHandlingStatus to) {
        var h=new PaymentAuditNotificationHandling();h.setHandlingStatus(from);setup(h,List.of());history();
        assertThat(service.changeStatus(1,2,"admin","token",to,"対応確認")).isTrue();
        assertThat(h.getHandlingStatus()).isEqualTo(to);
    }
}
