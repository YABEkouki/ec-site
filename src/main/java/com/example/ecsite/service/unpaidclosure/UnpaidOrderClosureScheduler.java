package com.example.ecsite.service.unpaidclosure;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.ecsite.config.UnpaidOrderClosureProperties;

/** Feature142-B: disabled unless app.unpaid-order-closure.enabled=true. Sends PAY.JP cancels. */
@Component
@ConditionalOnProperty(prefix = "app.unpaid-order-closure", name = "enabled", havingValue = "true")
public class UnpaidOrderClosureScheduler {
    private final UnpaidOrderClosureService service;
    private final UnpaidOrderClosureProperties properties;

    public UnpaidOrderClosureScheduler(UnpaidOrderClosureService service, UnpaidOrderClosureProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.unpaid-order-closure.fixed-delay:5m}")
    public void closeDue() {
        try {
            service.closeDue(properties.after(), properties.retryAfter(), properties.batchSize());
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Automatic unpaid order closure pass deferred. error={}",
                    failure.getClass().getSimpleName());
        }
    }
}
