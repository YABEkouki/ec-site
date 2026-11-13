package com.example.ecsite.service.orderchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Feature128 A-2: applies the 14:05 final decision to due order changes. Each operation is decided in its own
 * short transaction under Order -> operation -> Payments locks; no provider call is made here. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class OrderChangeFinalDecisionService {
    private static final Logger log=LoggerFactory.getLogger(OrderChangeFinalDecisionService.class);
    private final OrderReauthorizationTransactions results;
    public OrderChangeFinalDecisionService(OrderReauthorizationTransactions results){this.results=results;}
    /** Returns the number of operations newly decided. Failures are retried on the next run. */
    public int finalizeDue(int limit){
        int decided=0;
        for(var candidate:results.finalDecisionCandidates(limit)){
            try{
                if(results.finalizeAtDeadline(candidate.orderId(),candidate.operationId())==OrderReauthorizationTransactions.FinalDecisionResult.DECIDED)decided++;
            }catch(RuntimeException failure){
                log.warn("Order change final decision deferred. orderId={}, operationId={}",candidate.orderId(),candidate.operationId());
            }
        }
        return decided;
    }
}
