package com.example.ecsite.service.payment;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Feature135-B3: disabled unless app.payment.authorization-expiry-refresh.enabled=true. Sends read-only PAY.JP GETs.
 * Each pass is safe to repeat.
 */
@Component
@ConditionalOnProperty(prefix = "app.payment.authorization-expiry-refresh", name = "enabled", havingValue = "true")
public class AuthorizationExpiryRefreshScheduler {
    private final AuthorizationExpiryRefreshService service;

    public AuthorizationExpiryRefreshScheduler(AuthorizationExpiryRefreshService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.payment.authorization-expiry-refresh.fixed-delay:10m}")
    public void refresh() {
        try {
            service.runPass();
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Authorization expiry refresh pass deferred", failure);
        }
    }
}
