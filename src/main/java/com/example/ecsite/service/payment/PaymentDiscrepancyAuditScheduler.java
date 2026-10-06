package com.example.ecsite.service.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.payment.discrepancy-audit",
        name = "enabled",
        havingValue = "true")
public class PaymentDiscrepancyAuditScheduler {

    private final PaymentDiscrepancyAuditService auditService;

    public PaymentDiscrepancyAuditScheduler(
            PaymentDiscrepancyAuditService auditService) {
        this.auditService = auditService;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.payment.discrepancy-audit.fixed-delay}")
    public void auditPaymentDiscrepancies() {
        auditService.auditPayments();
    }
}
