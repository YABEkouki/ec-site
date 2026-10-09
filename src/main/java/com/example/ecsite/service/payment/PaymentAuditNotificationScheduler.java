package com.example.ecsite.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.payment.discrepancy-audit.notification", name = "enabled", havingValue = "true")
public class PaymentAuditNotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(PaymentAuditNotificationScheduler.class);
    private final PaymentAuditNotificationDeliveryService delivery;
    public PaymentAuditNotificationScheduler(PaymentAuditNotificationDeliveryService delivery) { this.delivery = delivery; }

    @Scheduled(fixedDelayString = "${app.payment.discrepancy-audit.notification.fixed-delay:1m}",
        scheduler = "paymentAuditNotificationTaskScheduler")
    public void tick() {
        try { delivery.tick(); }
        catch (RuntimeException failure) {
            // No throwable or exception message: SQL/SMTP diagnostics may contain sensitive values.
            log.atError().addKeyValue("failureCode", "NOTIFICATION_TICK_FAILED")
                .log("Payment audit notification tick failed; recovery will run on a later tick");
        }
    }
}
