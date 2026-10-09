package com.example.ecsite.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.example.ecsite.service.MailService;
import com.example.ecsite.service.payment.*;

class PaymentAuditNotificationSchedulingConfigurationTest {
    @Configuration(proxyBeanMethods=false) @EnableScheduling
    @EnableConfigurationProperties(PaymentDiscrepancyAuditNotificationProperties.class)
    static class Config {
        @Bean PaymentDiscrepancyAuditService audit() {return mock(PaymentDiscrepancyAuditService.class);}
        @Bean PaymentReconciliationService reconciliation() {return mock(PaymentReconciliationService.class);}
    }
    final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class,MailSenderAutoConfiguration.class))
        .withUserConfiguration(Config.class,PaymentAuditNotificationSchedulingConfiguration.class,
            PaymentAuditNotificationScheduler.class,PaymentDiscrepancyAuditScheduler.class,PaymentReconciliationScheduler.class,
            PaymentDiscrepancyAuditNotificationMailClient.class,MailService.class)
        .withPropertyValues("spring.mail.host=localhost","spring.mail.port=1025","app.mail.from=from@example.com","app.base-url=http://localhost:8080",
            "app.payment.discrepancy-audit.enabled=true","app.payment.discrepancy-audit.fixed-delay=20ms",
            "app.payment.reconciliation.enabled=true","app.payment.reconciliation.fixed-delay=20ms");
    @Test void offKeepsBootSingleSchedulerAndSharedMailSender() {
        runner.withBean("delivery",PaymentAuditNotificationDeliveryService.class,()->mock(PaymentAuditNotificationDeliveryService.class))
            .withPropertyValues("app.payment.discrepancy-audit.notification.enabled=false").run(c->{
            assertThat(c).hasNotFailed().hasSingleBean(JavaMailSender.class).hasSingleBean(ThreadPoolTaskScheduler.class)
                .doesNotHaveBean(PaymentAuditNotificationScheduler.class).doesNotHaveBean(PaymentDiscrepancyAuditNotificationMailClient.class);
            var scheduler=c.getBean("taskScheduler",ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(c.getBean(MailService.class),"mailSender")).isSameAs(c.getBean(JavaMailSender.class));
            assertThat(c.getBeanFactory().getBeanDefinition("taskScheduler").getResourceDescription()).contains("org.springframework.boot.autoconfigure.task.");
        });
    }
    @Test void onUsesDedicatedSchedulerWhileBlockedNotificationDoesNotStopExistingSchedulers() {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var audited=new CountDownLatch(3);var reconciled=new CountDownLatch(3);
        var notificationThread=new AtomicReference<String>();var auditThread=new AtomicReference<String>();var reconcileThread=new AtomicReference<String>();
        var delivery=mock(PaymentAuditNotificationDeliveryService.class);
        try {
            doAnswer(i->{notificationThread.set(Thread.currentThread().getName());entered.countDown();release.await(5,TimeUnit.SECONDS);return null;})
                .when(delivery).tick();
        } catch(Exception impossible){throw new AssertionError(impossible);}
        runner.withBean("delivery",PaymentAuditNotificationDeliveryService.class,()->delivery)
            .withPropertyValues("app.payment.discrepancy-audit.notification.enabled=true",
                "app.payment.discrepancy-audit.notification.recipients=admin@example.com",
                "app.payment.discrepancy-audit.notification.fixed-delay=10s").run(c->{
            try {
                assertThat(c).hasNotFailed().hasSingleBean(JavaMailSender.class);
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(c.getBean(MailService.class),"mailSender")).isSameAs(c.getBean(JavaMailSender.class));
                assertThat(c.getBeansOfType(ThreadPoolTaskScheduler.class)).containsOnlyKeys("taskScheduler","paymentAuditNotificationTaskScheduler");
                var legacy=c.getBean("taskScheduler",ThreadPoolTaskScheduler.class);
                var notification=c.getBean("paymentAuditNotificationTaskScheduler",ThreadPoolTaskScheduler.class);
                assertThat(legacy.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
                assertThat(notification.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
                doAnswer(i->{auditThread.set(Thread.currentThread().getName());audited.countDown();return null;}).when(c.getBean(PaymentDiscrepancyAuditService.class)).auditPayments();
                doAnswer(i->{reconcileThread.set(Thread.currentThread().getName());reconciled.countDown();return null;}).when(c.getBean(PaymentReconciliationService.class)).reconcilePendingTransactions();
                // The initial real @Scheduled invocation is already blocked on the dedicated notification scheduler.
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThat(audited.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(reconciled.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(notificationThread.get()).startsWith("payment-audit-notification-");
                assertThat(auditThread.get()).startsWith("scheduling-");assertThat(reconcileThread.get()).isEqualTo(auditThread.get());
            } finally {release.countDown();}
        });
    }

    @Test void blockedSmtpWorkerDoesNotStopAuditOrReconciliation() throws Exception {
        var client=mock(PaymentDiscrepancyAuditNotificationMailClient.class);
        var observer=mock(PaymentAuditNotificationObservationService.class);
        var transactions=mock(PaymentAuditNotificationDeliveryTransaction.class);
        var claim=new PaymentAuditNotificationDeliveryTransaction.Claim(42,UUID.randomUUID());
        var mail=new PaymentAuditNotificationMail("from@example.com",List.of("admin@example.com"),"監査の警告","本文");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var audited=new CountDownLatch(3);var reconciled=new CountDownLatch(3);
        var smtpThread=new AtomicReference<String>();var notificationThread=new AtomicReference<String>();
        doAnswer(i->{smtpThread.set(Thread.currentThread().getName());entered.countDown();release.await(10,TimeUnit.SECONDS);return null;})
            .when(client).send(mail);
        when(transactions.claim(any())).thenAnswer(i->{notificationThread.set(Thread.currentThread().getName());return Optional.of(claim);});
        when(transactions.prepare(claim)).thenReturn(Optional.of(mail));
        var p=new PaymentDiscrepancyAuditNotificationProperties(true,List.of("admin@example.com"),Duration.ofSeconds(10),Duration.ofMinutes(15),
            Duration.ofMinutes(5),3,Duration.ofMinutes(3),new PaymentDiscrepancyAuditNotificationProperties.Smtp(
                Duration.ofSeconds(5),Duration.ofSeconds(10),Duration.ofSeconds(10),Duration.ofMinutes(2)));
        try(var executor=new PaymentAuditNotificationSmtpExecutor(client)) {
            var delivery=new PaymentAuditNotificationDeliveryService(observer,transactions,executor,p);
            runner.withBean("delivery",PaymentAuditNotificationDeliveryService.class,()->delivery)
                .withPropertyValues("app.payment.discrepancy-audit.notification.enabled=true",
                    "app.payment.discrepancy-audit.notification.recipients=admin@example.com",
                    "app.payment.discrepancy-audit.notification.fixed-delay=10s").run(c->{
                try {
                    assertThat(c).hasNotFailed();
                    doAnswer(i->{audited.countDown();return null;}).when(c.getBean(PaymentDiscrepancyAuditService.class)).auditPayments();
                    doAnswer(i->{reconciled.countDown();return null;}).when(c.getBean(PaymentReconciliationService.class)).reconcilePendingTransactions();
                    assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                    assertThat(audited.await(3,TimeUnit.SECONDS)).isTrue();assertThat(reconciled.await(3,TimeUnit.SECONDS)).isTrue();
                    assertThat(smtpThread.get()).isEqualTo("payment-audit-smtp");
                    assertThat(notificationThread.get()).startsWith("payment-audit-notification-");
                    assertThat(executor.reserve()).isEmpty();verify(transactions,never()).complete(any(),any(),any());
                } finally {release.countDown();}
            });
        } finally {release.countDown();}
    }
}
