package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import com.example.ecsite.entity.*;

class PaymentAuditNotificationSmtpExecutorTest {
    final PaymentAuditNotificationMail mail=new PaymentAuditNotificationMail("from@example.com",List.of("admin@example.com"),"警告","本文");
    @Test void successAndFailureProduceOnlySafeResultCodes() {
        var client=mock(PaymentDiscrepancyAuditNotificationMailClient.class);
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client)) {
            try(var slot=executor.reserve().orElseThrow()) {
                assertThat(slot.send(mail,Duration.ofSeconds(1)).result()).isEqualTo(PaymentAuditNotificationAttemptResult.SUCCESS);
            }
        }
        doThrow(new MailSendException("secret token customer data")).when(client).send(mail);
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client);var slot=executor.reserve().orElseThrow()) {
            var result=slot.send(mail,Duration.ofSeconds(1));
            assertThat(result.result()).isEqualTo(PaymentAuditNotificationAttemptResult.FAILURE);
            assertThat(result.code()).isEqualTo(PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED);
            assertThat(result.toString()).doesNotContain("secret");
        }
    }
    @Test void timeoutKeepsSlotOccupiedUntilActualSmtpReturnsDespiteInterrupt() throws Exception {
        var client=mock(PaymentDiscrepancyAuditNotificationMailClient.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();
            try {while(release.getCount()>0)try {release.await();}catch(InterruptedException ignored) {}}
            finally {exited.countDown();}return null;
        }).when(client).send(mail);
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client);var slot=executor.reserve().orElseThrow()) {
            var result=slot.send(mail,Duration.ofMillis(50));
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            assertThat(result.result()).isEqualTo(PaymentAuditNotificationAttemptResult.UNKNOWN);
            assertThat(result.code()).isEqualTo(PaymentAuditNotificationFailureCode.SEND_DEADLINE_EXCEEDED);
            assertThat(executor.reserve()).isEmpty();slot.close();assertThat(executor.reserve()).isEmpty();
            release.countDown();assertThat(exited.await(1,TimeUnit.SECONDS)).isTrue();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            PaymentAuditNotificationSmtpExecutor.Slot next=null;
            while(next==null && System.nanoTime()<end) {next=executor.reserve().orElse(null);Thread.onSpinWait();}
            assertThat(next).isNotNull();next.close();
        } finally {release.countDown();}
    }
    @Test void reservationWithoutMailDoesNotSendAndSaturationHasNoQueue() {
        var client=mock(PaymentDiscrepancyAuditNotificationMailClient.class);
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client);var slot=executor.reserve().orElseThrow()) {
            assertThat(executor.reserve()).isEmpty();
        }
        verifyNoInteractions(client);
    }

    @Test void interruptedCallerCannotDispatchPendingSmtp() throws Exception {
        var client=mock(PaymentDiscrepancyAuditNotificationMailClient.class);
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client);var slot=executor.reserve().orElseThrow()) {
            Thread.currentThread().interrupt();
            try {assertThat(slot.send(mail,Duration.ofSeconds(1)).result()).isEqualTo(PaymentAuditNotificationAttemptResult.UNKNOWN);}
            finally {Thread.interrupted();}
            awaitWorkerExit(executor);
        }
        verifyNoInteractions(client);
    }
    @Test void expiredDeadlineCannotStartPendingSmtp() throws Exception {
        var client=mock(PaymentDiscrepancyAuditNotificationMailClient.class);
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client);var slot=executor.reserve().orElseThrow()) {
            assertThat(slot.send(mail,Duration.ZERO).result()).isEqualTo(PaymentAuditNotificationAttemptResult.UNKNOWN);
            awaitWorkerExit(executor);
        }
        verifyNoInteractions(client);
    }

    private static void awaitWorkerExit(PaymentAuditNotificationSmtpExecutor executor) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<end) {
            var next=executor.reserve();
            if(next.isPresent()){next.get().close();return;}
            Thread.sleep(1);
        }
        throw new AssertionError("SMTP worker did not exit");
    }
}
