package com.example.ecsite.service.unpaidclosure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resolves interrupted closures only; never starts one. Shares the payment reconciliation kill switch. */
@Component
@ConditionalOnProperty(prefix = "app.payment.reconciliation", name = "enabled", havingValue = "true")
public class UnpaidOrderClosureRecoveryScheduler {
    private final UnpaidOrderClosureService service;

    public UnpaidOrderClosureRecoveryScheduler(UnpaidOrderClosureService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.payment.reconciliation.fixed-delay}")
    public void recover() {
        try {
            service.recover(100);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unpaid order closure recovery deferred");
        }
    }
}
