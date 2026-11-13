package com.example.ecsite.service.orderchange;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.example.ecsite.payment.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Shared GET/prepare/dispatch/closure/completion sequence for an operation whose abort intent is already
 * recorded (any requester type). Never calls requestAbort itself. Extracted so both the customer withdrawal
 * command and automatic SYSTEM-triggered cancellation (Feature129-A) retry the exact same orchestration. */
final class RequestedAbortProgression {
    private final OrderReauthorizationTransactions results;
    private final PaymentGateway gateway;
    private final PlatformTransactionManager manager;
    private final Clock clock;
    RequestedAbortProgression(OrderReauthorizationTransactions results,PaymentGateway gateway,PlatformTransactionManager manager,Clock clock){
        this.results=results;this.gateway=gateway;this.manager=manager;this.clock=clock;
    }
    void advance(long order,long customer,UUID operation){
        var view=results.describe(order,operation);
        if("MANUAL_REVIEW".equals(view.state()))return;
        var completion=new RequestedAbortCompletionControl(results,manager);
        if(view.flowClosed()||"ABORTED".equals(view.state())){completion.complete(gateway,order,operation);return;}
        var facts=results.withdrawalFacts(order,customer,operation);
        if(!facts.authorized()&&!facts.terminalAuthorization())return;
        Long request=facts.abortRequest();
        if(facts.authorized()&&request==null){
            var expected=tx().execute(status->results.loadAbortPreparationExpected(order,operation));
            var observation=Objects.requireNonNull(gateway.observeAbortPreparation(Objects.requireNonNull(expected)));
            observation.requireTarget(order,operation);
            var committed=new AtomicBoolean();
            var saved=tx().execute(status->{
                var prepared=results.prepareTrustedAbort(observation);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void afterCompletion(int outcome){committed.set(outcome==STATUS_COMMITTED);}
                });return prepared;
            });
            if(!committed.get())throw new IllegalStateException("Abort request commit not confirmed");
            request=Objects.requireNonNull(saved).id();
            facts=results.withdrawalFacts(order,customer,operation);
        }
        // READY is never inferred from a missing management row; STARTED/UNKNOWN paths are GET-only.
        if(request!=null&&facts.ready())new RequestedAbortDispatchControl(results,manager,clock).send(gateway,request);
        if("MANUAL_REVIEW".equals(results.describe(order,operation).state()))return;
        var closure=new NewFlowClosureControl(results,manager);
        var result=closure.record(order,operation,closure.observe(gateway,order,operation));
        if(result==NewFlowClosureControl.RecordResult.RECORDED||result==NewFlowClosureControl.RecordResult.REPLAYED)
            completion.complete(gateway,order,operation);
    }
    private TransactionTemplate tx(){var tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);return tx;}
}
