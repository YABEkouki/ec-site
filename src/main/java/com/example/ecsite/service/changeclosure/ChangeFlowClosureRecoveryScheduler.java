package com.example.ecsite.service.changeclosure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resolves interrupted closures only; never starts one. Shares the payment reconciliation kill switch. */
@Component
@ConditionalOnProperty(prefix = "app.payment.reconciliation", name = "enabled", havingValue = "true")
public class ChangeFlowClosureRecoveryScheduler {
    private final ChangeFlowClosureService service;

    public ChangeFlowClosureRecoveryScheduler(ChangeFlowClosureService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.payment.reconciliation.fixed-delay}")
    public void recover() {
        try {
            service.recover(100);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Order change flow closure recovery deferred");
        }
    }
}
