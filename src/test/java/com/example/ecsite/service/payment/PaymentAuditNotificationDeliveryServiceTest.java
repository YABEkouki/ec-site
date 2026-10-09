package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.example.ecsite.entity.*;

class PaymentAuditNotificationDeliveryServiceTest {
    final PaymentAuditNotificationObservationService observer=mock(PaymentAuditNotificationObservationService.class);
    final PaymentAuditNotificationDeliveryTransaction transactions=mock(PaymentAuditNotificationDeliveryTransaction.class);
    final PaymentDiscrepancyAuditNotificationMailClient client=mock(PaymentDiscrepancyAuditNotificationMailClient.class);
    final PaymentAuditNotificationDeliveryTransaction.Claim claim=new PaymentAuditNotificationDeliveryTransaction.Claim(42,UUID.randomUUID());
    final PaymentAuditNotificationMail mail=new PaymentAuditNotificationMail("from@example.com",List.of("a@example.com"),"警告","本文");
    void eligible() {when(transactions.claim(any())).thenReturn(Optional.of(claim));when(transactions.prepare(claim)).thenReturn(Optional.of(mail));}
    @Test void tickObservesRecoversThenDeliversOneCommittedAttempt() {
        eligible();
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client)) {
            var service=new PaymentAuditNotificationDeliveryService(observer,transactions,executor,PaymentDiscrepancyAuditNotificationMailClientTest.properties());
            service.tick();
            var order=inOrder(observer,transactions,client);
            order.verify(observer).observe();order.verify(transactions).recoverExpired();order.verify(transactions).claim(any());
            order.verify(transactions).prepare(claim);order.verify(client).send(mail);
            order.verify(transactions).complete(claim,PaymentAuditNotificationAttemptResult.SUCCESS,null);
            verify(transactions,times(1)).claim(any());
        }
    }
    @Test void resultSaveFailureDoesNotRepeatSmtpWithinTick() {
        eligible();doThrow(new IllegalStateException("DB failure")).when(transactions).complete(claim,PaymentAuditNotificationAttemptResult.SUCCESS,null);
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client)) {
            var service=new PaymentAuditNotificationDeliveryService(observer,transactions,executor,PaymentDiscrepancyAuditNotificationMailClientTest.properties());
            assertThatThrownBy(service::tick).isInstanceOf(IllegalStateException.class);
            verify(client,times(1)).send(mail);verify(transactions,times(1)).prepare(claim);
        }
    }
    @Test void executorSaturationStillObservesAndRecoversButDoesNotClaimOrConsumeAttempt() {
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client);var slot=executor.reserve().orElseThrow()) {
            new PaymentAuditNotificationDeliveryService(observer,transactions,executor,PaymentDiscrepancyAuditNotificationMailClientTest.properties()).tick();
            verify(observer).observe();verify(transactions).recoverExpired();verify(transactions,never()).claim(any());
            verifyNoInteractions(client);
        }
    }
    @Test void prepareCommitFailureNeverStartsSmtp() {
        when(transactions.claim(any())).thenReturn(Optional.of(claim));when(transactions.prepare(claim)).thenThrow(new IllegalStateException("commit failed"));
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client)) {
            var service=new PaymentAuditNotificationDeliveryService(observer,transactions,executor,PaymentDiscrepancyAuditNotificationMailClientTest.properties());
            assertThatThrownBy(service::tick).isInstanceOf(IllegalStateException.class);verifyNoInteractions(client);
        }
    }
    @Test void cancelledPreflightDoesNotSend() {
        when(transactions.claim(any())).thenReturn(Optional.of(claim));when(transactions.prepare(claim)).thenReturn(Optional.empty());
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client)) {
            new PaymentAuditNotificationDeliveryService(observer,transactions,executor,PaymentDiscrepancyAuditNotificationMailClientTest.properties()).tick();
            verifyNoInteractions(client);verify(transactions,never()).complete(any(),any(),any());
        }
    }
}
