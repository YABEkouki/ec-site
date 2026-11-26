package com.example.ecsite.service.changeclosure;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.ecsite.config.OrderChangeClosureProperties;

/** Feature143-B: disabled unless app.order-change-closure.enabled=true. Sends PAY.JP cancels. */
@Component
@ConditionalOnProperty(prefix = "app.order-change-closure", name = "enabled", havingValue = "true")
public class ChangeFlowClosureScheduler {
    private final ChangeFlowClosureService service;
    private final OrderChangeClosureProperties properties;

    public ChangeFlowClosureScheduler(ChangeFlowClosureService service, OrderChangeClosureProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.order-change-closure.fixed-delay:5m}")
    public void closeDue() {
        try {
            service.closeDue(properties.after(), properties.retryAfter(), properties.batchSize());
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Automatic order change flow closure pass deferred. error={}",
                    failure.getClass().getSimpleName());
        }
    }
}
