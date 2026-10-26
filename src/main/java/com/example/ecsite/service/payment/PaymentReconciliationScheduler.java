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

    private final com.example.ecsite.service.orderchange.OrderIncreaseService increases;
    public PaymentReconciliationScheduler(
            PaymentReconciliationService reconciliationService,
            com.example.ecsite.service.orderchange.OrderIncreaseService increases) {

        this.reconciliationService = reconciliationService;
        this.increases=increases;
    }

    @Scheduled(
            fixedDelayString = "${app.payment.reconciliation.fixed-delay}")
    public void reconcile() {

        try{reconciliationService.reconcilePendingTransactions();}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Payment reconciliation scan deferred");}
        final java.util.List<com.example.ecsite.service.orderchange.OrderChangeStore.ExpiringOperation> candidates;
        try{candidates=increases.expiredReservedCandidates(100);}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Reserved change expiry scan deferred");return;}
        for(var candidate:candidates){
            try{increases.expireReserved(candidate.orderId(),candidate.operationId());}
            catch(RuntimeException failure){
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Reserved change expiry deferred. orderId={}, operationId={}",
                    candidate.orderId(),candidate.operationId());
            }
        }
    }
}
