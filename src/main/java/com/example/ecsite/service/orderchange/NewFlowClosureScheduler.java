package com.example.ecsite.service.orderchange;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Shares the existing payment reconciliation kill switch; disabled means no cursor or business writes. */
@Component
@ConditionalOnProperty(prefix="app.payment.reconciliation",name="enabled",havingValue="true")
public class NewFlowClosureScheduler {
    private final NewFlowClosureReconciliationService service;
    public NewFlowClosureScheduler(NewFlowClosureReconciliationService service){this.service=service;}
    @Scheduled(fixedDelayString="${app.payment.reconciliation.fixed-delay}")
    public void scan(){
        try{service.scan();}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("New Flow closure scan deferred");}
    }
}
