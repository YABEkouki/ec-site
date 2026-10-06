package com.example.ecsite.service.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.payment.reconciliation",
        name = "enabled",
        havingValue = "true")
public class PaymentReconciliationScheduler {

    private final PaymentReconciliationService reconciliationService;

    public PaymentReconciliationScheduler(
            PaymentReconciliationService reconciliationService) {

        this.reconciliationService = reconciliationService;
    }

    @Scheduled(
            fixedDelayString = "${app.payment.reconciliation.fixed-delay}")
    public void reconcile() {

        reconciliationService.reconcilePendingTransactions();
    }
}
