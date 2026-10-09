package com.example.ecsite.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** OFF: leaves Boot auto-configuration intact. ON: explicitly preserves the legacy default scheduler. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.payment.discrepancy-audit.notification", name = "enabled", havingValue = "true")
public class PaymentAuditNotificationSchedulingConfiguration {
    @Bean(name = "taskScheduler")
    @Primary
    public ThreadPoolTaskScheduler taskScheduler() {
        return scheduler("scheduling-");
    }

    @Bean(name = "paymentAuditNotificationTaskScheduler")
    public ThreadPoolTaskScheduler notificationTaskScheduler() {
        return scheduler("payment-audit-notification-");
    }

    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
