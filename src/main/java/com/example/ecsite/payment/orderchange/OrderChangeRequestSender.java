package com.example.ecsite.payment.orderchange;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import com.example.ecsite.payment.payjp.*;

/** Unconnected transport component: no controller, scheduler or financial result application.
 * Consuming eligibility in a separate committed transaction prevents replay after crashes or unknown results. */
public class OrderChangeRequestSender {
    private final OrderChangeFinancialRequestStore store;
    private final com.example.ecsite.payment.PaymentGateway gateway;
    private final TransactionTemplate start;
    /** Unforgeable by callers; produced only after the persistence-backed validation transaction commits. */
    public static final class Dispatch {
        private final StoredOrderChangeRequest request;
        private Dispatch(StoredOrderChangeRequest request){this.request=request;}
        public StoredOrderChangeRequest request(){return request;}
        @Override public String toString(){return "OrderChangeDispatch[redacted]";}
    }
    public OrderChangeRequestSender(OrderChangeFinancialRequestStore store,com.example.ecsite.payment.PaymentGateway gateway,PlatformTransactionManager manager){
        this.store=store;this.gateway=gateway;start=new TransactionTemplate(manager);
        start.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional(propagation=Propagation.NEVER)
    public PayJpPaymentFlowObservation send(long requestId){return send(requestId,null);}
    @Transactional(propagation=Propagation.NEVER)
    public PayJpPaymentFlowObservation send(long requestId,com.example.ecsite.service.order.evidence.OrderEditApplicationPlan.Ownership owner){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Sender requires no transaction");
        // A lost/failed commit response throws here: no dispatch token and no provider call.
        StoredOrderChangeRequest saved;
        try{saved=start.execute(status->owner==null?store.findForDispatch(requestId):store.findForDispatch(requestId,owner));
        }catch(RuntimeException failure){
            // A deadline violation, even from the deferred check at commit, rolled the STARTED permit back.
            if(!(failure instanceof OrderChangeFinancialRequestStore.DispatchDeadlinePassed)
                    &&OrderChangeFinancialRequestStore.isDeadlineViolation(failure))
                throw new OrderChangeFinancialRequestStore.DispatchDeadlinePassed();
            throw failure;
        }
        return gateway.sendOrderChangeRequest(new Dispatch(saved));
    }
}
