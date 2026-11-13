package com.example.ecsite.service.orderchange;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs with payment reconciliation: both converge order changes and share the same operational switch. */
@Component
@ConditionalOnProperty(prefix="app.payment.reconciliation",name="enabled",havingValue="true")
public class OrderChangeFinalDecisionScheduler {
    private final OrderChangeFinalDecisionService decisions;
    public OrderChangeFinalDecisionScheduler(OrderChangeFinalDecisionService decisions){this.decisions=decisions;}
    @Scheduled(fixedDelayString="${app.payment.reconciliation.fixed-delay}")
    public void finalizeDue(){
        try{decisions.finalizeDue(100);}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Order change final decision scan deferred");}
    }
}
