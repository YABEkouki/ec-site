package com.example.ecsite.config;

import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.example.ecsite.service.payment.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentAuditNotificationManualResendSchedulingTest {
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(PaymentDiscrepancyAuditNotificationProperties.class)
    static class Beans {
        @Bean PaymentAuditNotificationManualResendDeliveryService delivery(){return mock(PaymentAuditNotificationManualResendDeliveryService.class);}
        @Bean PaymentDiscrepancyAuditNotificationMailClient client(){return mock(PaymentDiscrepancyAuditNotificationMailClient.class);}
    }
    @Configuration(proxyBeanMethods=false) @EnableScheduling static class Scheduling {}
    final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(Beans.class,Scheduling.class,PaymentAuditNotificationSchedulingConfiguration.class,PaymentAuditNotificationManualResendScheduler.class,PaymentAuditNotificationSmtpExecutor.class)
        .withPropertyValues("app.payment.discrepancy-audit.notification.recipients=fixture@example.invalid");
    @ParameterizedTest @ValueSource(booleans={false,true}) void onAndOffPreserveExistingSchedulers(boolean on){runner.withPropertyValues("app.payment.discrepancy-audit.notification.enabled="+on).run(c->{
        assertThat(c).hasNotFailed().hasSingleBean(PaymentAuditNotificationManualResendScheduler.class);
        var scheduler=c.getBean(PaymentAuditNotificationManualResendScheduler.class);assertThat(scheduler.isRunning()).isTrue();
        var owned=(ThreadPoolTaskScheduler)org.springframework.test.util.ReflectionTestUtils.getField(scheduler,"scheduler");assertThat(owned.getThreadNamePrefix()).isEqualTo("payment-audit-manual-resend-");assertThat(owned.getPoolSize()).isEqualTo(1);
        assertThat(c.getBeansOfType(ThreadPoolTaskScheduler.class).size()).isEqualTo(on?2:1);
        if(on)assertThat(c).hasSingleBean(PaymentAuditNotificationSmtpExecutor.class);else assertThat(c).doesNotHaveBean(PaymentAuditNotificationSmtpExecutor.class);
        scheduler.tick();verify(c.getBean(PaymentAuditNotificationManualResendDeliveryService.class)).recoverExpired();if(on)verify(c.getBean(PaymentAuditNotificationManualResendDeliveryService.class)).deliverOne();else verify(c.getBean(PaymentAuditNotificationManualResendDeliveryService.class),never()).deliverOne();
        scheduler.stop();assertThat(scheduler.isRunning()).isFalse();scheduler.start();assertThat(scheduler.isRunning()).isTrue();
    });}
    @Test void noSchedulingInfrastructureDoesNotStartThread(){new ApplicationContextRunner().withUserConfiguration(Beans.class,PaymentAuditNotificationManualResendScheduler.class).run(c->{assertThat(c).hasNotFailed();assertThat(c.getBean(PaymentAuditNotificationManualResendScheduler.class).isRunning()).isFalse();});}
}
