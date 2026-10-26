package com.example.ecsite.service.orderchange;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.example.ecsite.payment.PaymentGateway;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Internal completion only: no controller, automatic worker, financial POST or result inference. */
final class RequestedAbortCompletionControl {
    enum Result { COMPLETED, REPLAYED }
    private final OrderReauthorizationTransactions results;
    private final TransactionTemplate transaction;
    RequestedAbortCompletionControl(OrderReauthorizationTransactions results,PlatformTransactionManager manager){
        this.results=Objects.requireNonNull(results);transaction=new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    Result complete(PaymentGateway gateway,long orderId,UUID operationId){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Completion requires no ambient transaction");
        Objects.requireNonNull(gateway);Objects.requireNonNull(operationId);
        var expected=transaction.execute(tx->results.loadAbortCompletionExpected(orderId,operationId));
        if(expected==null)return Result.REPLAYED; // Durable matching receipt, never a fresh stock release.
        var observation=Objects.requireNonNull(gateway.observeOldAuthorization(expected)); // GET outside either Tx.
        observation.requireTarget(orderId,operationId);
        var committed=new AtomicBoolean();
        var result=transaction.execute(tx->{
            var completed=results.completeRequestedAbort(observation);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){committed.set(status==STATUS_COMMITTED);}
            });
            return completed;
        });
        if(!committed.get())throw new IllegalStateException("Abort completion commit not confirmed");
        return Objects.requireNonNull(result);
    }
}
