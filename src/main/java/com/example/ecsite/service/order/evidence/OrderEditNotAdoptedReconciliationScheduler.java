package com.example.ecsite.service.order.evidence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs with payment reconciliation: shares the same operational kill switch. Safe even while the
 * Feature125 order-edit runtime is STOPPED — reconcile() then reports BLOCKED/COMMIT_UNKNOWN and writes
 * nothing, since the runtime gate is enforced by the database functions themselves. */
@Component
@ConditionalOnProperty(prefix="app.payment.reconciliation",name="enabled",havingValue="true")
public class OrderEditNotAdoptedReconciliationScheduler {
    private final OrderEditNotAdoptedReconciliationService service;
    public OrderEditNotAdoptedReconciliationScheduler(OrderEditNotAdoptedReconciliationService service){this.service=service;}
    @Scheduled(fixedDelayString="${app.payment.reconciliation.fixed-delay}")
    public void reconcileDue(){
        try{service.reconcileDue(100);}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Order edit not-adopted reconciliation scan deferred");}
    }
}
