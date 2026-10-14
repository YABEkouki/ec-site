package com.example.ecsite.service.payment;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentAuditNotificationManualResendDeliveryServiceTest {
    static PaymentDiscrepancyAuditNotificationProperties properties(boolean on){return new PaymentDiscrepancyAuditNotificationProperties(on,on?List.of("fixture@example.invalid"):List.of(),Duration.ofMinutes(1),Duration.ofMinutes(15),Duration.ofMinutes(5),3,Duration.ofMinutes(3),new PaymentDiscrepancyAuditNotificationProperties.Smtp(Duration.ofSeconds(5),Duration.ofSeconds(10),Duration.ofSeconds(10),Duration.ofMinutes(2)));}
    @Test void offDoesNotLookupExecutorOrClaim(){
        var tx=mock(PaymentAuditNotificationManualResendDeliveryTransaction.class);var provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        var service=new PaymentAuditNotificationManualResendDeliveryService(tx,provider,properties(false));service.deliverOne();verifyNoInteractions(tx,provider);
    }
    @Test void busySharedExecutorDoesNotClaim(){var tx=mock(PaymentAuditNotificationManualResendDeliveryTransaction.class);var smtp=mock(PaymentAuditNotificationSmtpExecutor.class);when(smtp.reserve()).thenReturn(Optional.empty());var factory=new StaticListableBeanFactory();factory.addBean("smtp",smtp);var service=new PaymentAuditNotificationManualResendDeliveryService(tx,factory.getBeanProvider(PaymentAuditNotificationSmtpExecutor.class),properties(true));service.deliverOne();verifyNoInteractions(tx);}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void automaticAndManualUseOneActualWorker(boolean automaticFirst) throws Exception {
        var client=mock(PaymentDiscrepancyAuditNotificationMailClient.class);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var active=new java.util.concurrent.atomic.AtomicInteger();var maximum=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(i->{int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);entered.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}finally{active.decrementAndGet();}return null;}).when(client).send(any());
        var automaticTx=mock(PaymentAuditNotificationDeliveryTransaction.class);var observer=mock(PaymentAuditNotificationObservationService.class);var manualTx=mock(PaymentAuditNotificationManualResendDeliveryTransaction.class);
        var mail=new PaymentAuditNotificationMail("fixture@example.invalid",List.of("fixture@example.invalid"),"検証","本文");var autoClaim=new PaymentAuditNotificationDeliveryTransaction.Claim(1,UUID.randomUUID());var manualClaim=new PaymentAuditNotificationManualResendDeliveryTransaction.Claim(2,1,UUID.randomUUID());
        when(automaticTx.claim(any())).thenReturn(Optional.of(autoClaim));when(automaticTx.prepare(autoClaim)).thenReturn(Optional.of(mail));when(manualTx.claim(any())).thenReturn(Optional.of(manualClaim));when(manualTx.prepare(manualClaim)).thenReturn(Optional.of(mail));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try(var executor=new PaymentAuditNotificationSmtpExecutor(client)){
            var factory=new StaticListableBeanFactory();factory.addBean("smtp",executor);var manual=new PaymentAuditNotificationManualResendDeliveryService(manualTx,factory.getBeanProvider(PaymentAuditNotificationSmtpExecutor.class),properties(true));var automatic=new PaymentAuditNotificationDeliveryService(observer,automaticTx,executor,properties(true));
            try{var running=pool.submit(()->{if(automaticFirst)automatic.tick();else manual.deliverOne();});assertThat(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();if(automaticFirst){manual.deliverOne();verify(manualTx,never()).claim(any());}else{automatic.tick();verify(automaticTx,never()).claim(any());}assertThat(maximum.get()).isEqualTo(1);release.countDown();running.get(3,java.util.concurrent.TimeUnit.SECONDS);verify(client,times(1)).send(any());}finally{release.countDown();pool.shutdownNow();}
        }
    }
}
