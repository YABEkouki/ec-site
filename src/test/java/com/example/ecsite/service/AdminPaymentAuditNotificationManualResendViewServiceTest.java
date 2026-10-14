package com.example.ecsite.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminPaymentAuditNotificationManualResendViewServiceTest {
    @Test void recipientsAreServerIntersectedAndCompletelyMasked(){
        var acceptance=mock(AdminPaymentAuditNotificationManualResendService.class);var notifications=mock(PaymentAuditNotificationRepository.class);var requests=mock(PaymentAuditNotificationManualResendRepository.class);var attempts=mock(PaymentAuditNotificationManualResendAttemptRepository.class);var props=mock(PaymentDiscrepancyAuditNotificationProperties.class);
        var n=mock(PaymentAuditNotification.class);when(n.getRecipients()).thenReturn(new String[]{"a@X", "a@x", "ü@example.org", "invalid", "removed@old.invalid"});when(notifications.findById(1L)).thenReturn(Optional.of(n));when(props.recipients()).thenReturn(List.of("a@x","ü@example.org","new@example.invalid"));when(requests.findByNotificationIdOrderByRequestNoAsc(1L)).thenReturn(List.of());when(acceptance.prepare(1,7,false)).thenReturn(new AdminPaymentAuditNotificationManualResendService.Eligibility(true,null,true,false,null));
        var view=new AdminPaymentAuditNotificationManualResendViewService(acceptance,notifications,requests,attempts,props).read(1,7,false);assertThat(view.maskedRecipients()).containsExactly("***@***","***@***");assertThat(view.toString()).doesNotContain("@","ü","old.invalid");verify(acceptance).prepare(1,7,false);verifyNoInteractions(attempts);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(PaymentAuditNotificationAttemptResult.class)
    void historiesUseRequestIdsForAttemptLookupAndConvertJst(PaymentAuditNotificationAttemptResult result){
        var acceptance=mock(AdminPaymentAuditNotificationManualResendService.class);var notifications=mock(PaymentAuditNotificationRepository.class);var requests=mock(PaymentAuditNotificationManualResendRepository.class);var attempts=mock(PaymentAuditNotificationManualResendAttemptRepository.class);var props=mock(PaymentDiscrepancyAuditNotificationProperties.class);
        var n=mock(PaymentAuditNotification.class);when(n.getRecipients()).thenReturn(new String[]{"secret@example.invalid"});when(notifications.findById(42L)).thenReturn(Optional.of(n));when(props.recipients()).thenReturn(List.of("secret@example.invalid"));
        var one=mock(PaymentAuditNotificationManualResend.class);var two=mock(PaymentAuditNotificationManualResend.class);
        when(one.getId()).thenReturn(101L);when(one.getRequestNo()).thenReturn(1);when(one.getStatus()).thenReturn(PaymentAuditNotificationManualResendStatus.CANCELLED);when(one.getRequestedAt()).thenReturn(java.time.Instant.parse("2026-10-13T00:00:00Z"));when(one.getReason()).thenReturn("reason-one");
        when(two.getId()).thenReturn(202L);when(two.getRequestNo()).thenReturn(2);when(two.getStatus()).thenReturn(PaymentAuditNotificationManualResendStatus.UNKNOWN);when(two.getReason()).thenReturn("reason-two");when(two.getRequestedAt()).thenReturn(java.time.Instant.parse("2026-10-13T00:30:00Z"));
        when(requests.findByNotificationIdOrderByRequestNoAsc(42L)).thenReturn(List.of(one,two));when(attempts.findByManualResendId(101L)).thenReturn(Optional.empty());var a=new PaymentAuditNotificationManualResendAttempt();a.setResult(result);a.setRecipients(new String[]{"secret@example.invalid"});a.setBody("secret-body");a.setSubject("secret-subject");when(attempts.findByManualResendId(202L)).thenReturn(Optional.of(a));
        var view=new AdminPaymentAuditNotificationManualResendViewService(acceptance,notifications,requests,attempts,props).read(42,7,false);
        assertThat(view.histories()).extracting(AdminPaymentAuditNotificationManualResendViewService.History::requestNo).containsExactly(1,2);
        assertThat(view.histories().getFirst().attemptResult()).isNull();assertThat(view.histories().getLast().attemptResult()).isEqualTo(result);
        assertThat(view.histories().getFirst().requestedAt()).isEqualTo(java.time.LocalDateTime.of(2026,10,13,9,0));assertThat(view.histories().getLast().reason()).isEqualTo("reason-two");assertThat(view.histories().getFirst().startedAt()).isNull();assertThat(view.histories().getFirst().finishedAt()).isNull();
        assertThat(view.toString()+view.histories()).doesNotContain("secret@example.invalid","secret-body","secret-subject","reason-one","reason-two");verify(attempts).findByManualResendId(101L);verify(attempts).findByManualResendId(202L);
    }
}
