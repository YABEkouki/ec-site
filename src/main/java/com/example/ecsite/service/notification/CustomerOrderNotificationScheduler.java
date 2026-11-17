package com.example.ecsite.service.notification;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.example.ecsite.config.CustomerOrderNotificationProperties;

/**
 * Feature133-B: disabled unless app.customer-notification.enabled=true. Runs on its own single thread so a slow
 * SMTP server never delays the shared scheduler (reconciliation, warehouse export and import).
 */
@Component
@ConditionalOnProperty(prefix = "app.customer-notification", name = "enabled", havingValue = "true")
public class CustomerOrderNotificationScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(CustomerOrderNotificationScheduler.class);
    private final CustomerOrderNotificationDeliveryService delivery;
    private final CustomerOrderNotificationProperties properties;
    private ScheduledExecutorService executor;

    public CustomerOrderNotificationScheduler(CustomerOrderNotificationDeliveryService delivery,
            CustomerOrderNotificationProperties properties) {
        this.delivery = delivery; this.properties = properties;
    }

    void tick() {
        try { delivery.runPass(); }
        catch (RuntimeException failure) {
            // No exception text: SQL or SMTP diagnostics may contain addresses or mail content.
            log.atWarn().addKeyValue("failureCode", "CUSTOMER_NOTIFICATION_PASS_FAILED")
                    .log("Customer notification pass deferred; a later pass retries");
        }
    }

    @Override public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "customer-notification"); thread.setDaemon(true); return thread;
        });
        long delay = properties.fixedDelay().toMillis();
        executor.scheduleWithFixedDelay(this::tick, delay, delay, TimeUnit.MILLISECONDS);
    }

    @Override public synchronized void stop() {
        if (executor == null) return;
        executor.shutdown();
        try { executor.awaitTermination(30, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        executor = null;
    }

    @Override public synchronized boolean isRunning() { return executor != null; }
}
