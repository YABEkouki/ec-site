package com.example.ecsite.service.orderchange;

import java.util.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.orderchange.*;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;

/** Internal business orchestration only. No customer endpoint, scheduler, automatic confirm or new-key recovery.
 * Every provider call is outside the short independent transactions in the result component. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class OrderReauthorizationService {
    private final OrderReauthorizationTransactions results;
    private final PaymentGateway gateway;
    private final OrderChangeRequestSender sender;
    public OrderReauthorizationService(OrderReauthorizationTransactions results,PaymentGateway gateway,
            OrderChangeFinancialRequestStore requests,PlatformTransactionManager manager){
        this.results=results;this.gateway=gateway;this.sender=new OrderChangeRequestSender(requests,gateway,manager);
    }
    public OrderReauthorizationTransactions.View advance(long orderId,UUID operationId){
        var view=results.prepare(orderId,operationId);
        if(stopped(view))return view;
        if(view.newFlow()==null){
            try{
                var created=sender.send(Objects.requireNonNull(view.createRequest()));
                view=results.attachFlow(orderId,operationId,created==null?null:created.id());
            }catch(PaymentGatewayException failure){return results.failed(orderId,operationId,true,failure.category());
            }catch(IllegalStateException|org.springframework.dao.InvalidDataAccessApiUsageException failure){
                return results.dispatchBlocked(orderId,operationId,true);
            }
            if(stopped(view))return view;
        }
        // GET, not the CREATE response or a webhook event name, establishes authorization.
        if(List.of("AUTHORIZING","AUTH_UNKNOWN","REQUIRES_ACTION","AUTHORIZED").contains(view.state())){
            try{
                var observed=gateway.observeOrderChangeFlow(view.newFlow());
                view=results.authorization(orderId,operationId,observed);
                if("AUTHORIZED".equals(view.state())){
                    var old=gateway.observeOrderChangeFlow(view.oldFlow());
                    view=results.decide(orderId,operationId,observed,old);
                }
            }catch(PaymentGatewayException failure){return results.describe(orderId,operationId);}
        }
        if("REQUIRES_ACTION".equals(view.state())||stopped(view))return view;
        if(List.of("OLD_CANCEL_PENDING","OLD_CANCEL_UNKNOWN","ABORTING","ABORT_UNKNOWN").contains(view.state())){
            boolean abort=view.state().startsWith("ABORT");
            try{sender.send(Objects.requireNonNull(abort?view.abortRequest():view.oldCancelRequest()));
            }catch(PaymentGatewayException unknown){
                // Even a lost Cancel response may have succeeded. Observe before choosing any retry.
                results.cancelFailed(orderId,operationId,abort,unknown.category());
            }catch(IllegalStateException|org.springframework.dao.InvalidDataAccessApiUsageException blocked){
                // A closed POST window still permits a safe GET to discover completed cancellation.
                view=results.dispatchBlocked(orderId,operationId,false);
            }
            try{view=results.cancellation(orderId,operationId,abort,gateway.observeOrderChangeFlow(abort?view.newFlow():view.oldFlow()));
            }catch(PaymentGatewayException unknown){return results.describe(orderId,operationId);}
        }
        if("COMMIT_PENDING".equals(view.state()))return commitObserved(view);
        return view;
    }
    private OrderReauthorizationTransactions.View commitObserved(OrderReauthorizationTransactions.View view){
        PayJpPaymentFlowObservation observed;
        try{observed=gateway.observeOrderChangeFlow(view.newFlow());}catch(PaymentGatewayException unknown){return view;}
        // Deliberately do not swallow DB failures: COMMIT_PENDING remains durable and the whole switch rolls back.
        return results.complete(view.orderId(),view.id(),observed);
    }
    /** GET-only result convergence used by webhook, reconciliation and explicit restart recovery. */
    public PaymentSynchronizationOutcome synchronize(PaymentOperationSnapshot snapshot){
        if(!PaymentTransaction.isOrderChangePurpose(snapshot.type(),snapshot.purpose()))return PaymentSynchronizationOutcome.SKIPPED;
        final OrderReauthorizationTransactions.View initial;
        try{initial=results.forSnapshot(snapshot);}catch(IllegalArgumentException conflict){return PaymentSynchronizationOutcome.CONFLICT;}
        if(stopped(initial))return PaymentSynchronizationOutcome.SKIPPED;
        if(snapshot.providerPaymentId()==null)return PaymentSynchronizationOutcome.WAITING;
        var observed=gateway.observeOrderChangeFlow(snapshot.providerPaymentId());
        var view=snapshot.purpose()==PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION
            ?results.authorization(initial.orderId(),initial.id(),observed)
            :results.cancellation(initial.orderId(),initial.id(),snapshot.purpose()==PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT,observed);
        if("COMMIT_PENDING".equals(view.state()))view=commitObserved(view);
        return List.of("AUTHORIZING","AUTH_UNKNOWN","REQUIRES_ACTION","OLD_CANCEL_UNKNOWN","ABORT_UNKNOWN").contains(view.state())
            ?PaymentSynchronizationOutcome.WAITING:PaymentSynchronizationOutcome.APPLIED;
    }
    /** Administrator/internal GET-only recovery. Unknown CREATE IDs remain stopped; no key is replaced. */
    public OrderReauthorizationTransactions.View reconcile(long orderId,UUID operationId){
        var view=results.describe(orderId,operationId);
        if(List.of("COMPLETED","ABORTED").contains(view.state())||view.newFlow()==null)return view;
        if(view.abortRequest()!=null)view=results.cancellation(orderId,operationId,true,gateway.observeOrderChangeFlow(view.newFlow()));
        else if(view.oldCancelRequest()!=null)view=results.cancellation(orderId,operationId,false,gateway.observeOrderChangeFlow(view.oldFlow()));
        else view=results.authorization(orderId,operationId,gateway.observeOrderChangeFlow(view.newFlow()));
        return "COMMIT_PENDING".equals(view.state())?commitObserved(view):view;
    }
    private boolean stopped(OrderReauthorizationTransactions.View view){return List.of("COMPLETED","ABORTED","MANUAL_REVIEW").contains(view.state());}
}
