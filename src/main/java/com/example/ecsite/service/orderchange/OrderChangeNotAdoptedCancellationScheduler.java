package com.example.ecsite.service.orderchange;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs with payment reconciliation: shares the same operational kill switch. */
@Component
@ConditionalOnProperty(prefix="app.payment.reconciliation",name="enabled",havingValue="true")
public class OrderChangeNotAdoptedCancellationScheduler {
    private final OrderChangeNotAdoptedCancellationService service;
    public OrderChangeNotAdoptedCancellationScheduler(OrderChangeNotAdoptedCancellationService service){this.service=service;}
    @Scheduled(fixedDelayString="${app.payment.reconciliation.fixed-delay}")
    public void cancelDue(){
        try{service.cancelDue(100);}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Order change not-adopted cancellation scan deferred");}
    }
}
