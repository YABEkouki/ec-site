package com.example.ecsite.payment.orderchange;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import com.example.ecsite.payment.payjp.*;

/** Unconnected transport component: no controller, scheduler or financial result application.
 * Loading in a separate committed transaction prevents sending unsaved/rolled-back requests. */
public class OrderChangeRequestSender {
    private final OrderChangeFinancialRequestStore store;
    private final com.example.ecsite.payment.PaymentGateway gateway;
    private final TransactionTemplate read;
    /** Unforgeable by callers; produced only after the persistence-backed validation transaction commits. */
    public static final class Dispatch {
        private final StoredOrderChangeRequest request;
        private Dispatch(StoredOrderChangeRequest request){this.request=request;}
        public StoredOrderChangeRequest request(){return request;}
        @Override public String toString(){return "OrderChangeDispatch[redacted]";}
    }
    public OrderChangeRequestSender(OrderChangeFinancialRequestStore store,com.example.ecsite.payment.PaymentGateway gateway,PlatformTransactionManager manager){
        this.store=store;this.gateway=gateway;read=new TransactionTemplate(manager);
        read.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional(propagation=Propagation.NEVER)
    public PayJpPaymentFlowObservation send(long requestId){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Sender requires no transaction");
        var saved=read.execute(status->store.findForDispatch(requestId));
        return gateway.sendOrderChangeRequest(new Dispatch(saved));
    }
}
