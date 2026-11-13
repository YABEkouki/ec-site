package com.example.ecsite.service.orderchange;

import java.time.Clock;
import java.util.*;
import com.example.ecsite.payment.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;

/** Customer command boundary. GET displays never execute this workflow or mint a financial request. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class OrderChangeWithdrawalService {
    public record Status(boolean requested,boolean canRequest,boolean manualReviewRequired){}
    private final OrderIncreaseService increases;
    private final OrderReauthorizationTransactions results;
    private final PaymentGateway gateway;
    private final PlatformTransactionManager manager;
    private final Clock clock;
    public OrderChangeWithdrawalService(OrderIncreaseService increases,OrderReauthorizationTransactions results,
            PaymentGateway gateway,PlatformTransactionManager manager,Clock clock){
        this.increases=increases;this.results=results;this.gateway=gateway;this.manager=manager;this.clock=clock;
    }
    public Status status(long order,long customer,UUID operation){
        increases.status(order,customer,operation);
        var view=results.describe(order,operation);
        boolean complete="ABORTED".equals(view.state());
        return new Status(view.abortRequested(),!view.abortRequested()&&view.oldCancelRequest()==null&&List.of(
            "RESERVED","AUTHORIZING","REQUIRES_ACTION","AUTH_UNKNOWN","AUTHORIZED","MANUAL_REVIEW").contains(view.state()),
            "MANUAL_REVIEW".equals(view.state())||view.abortRequested()&&!complete);
    }
    public void withdraw(long order,long customer,UUID operation,UUID requestId,long expectedVersion){
        Objects.requireNonNull(requestId);if(expectedVersion<0)throw new IllegalArgumentException("Invalid withdrawal version");
        increases.status(order,customer,operation); // Ownership BEFORE every financial observation.
        var facts=results.withdrawalFacts(order,customer,operation);
        if(facts.local()){
            increases.withdrawReserved(order,customer,operation,expectedVersion);return;
        }
        var receipt=results.requestAbort(new OrderReauthorizationTransactions.AbortRequest(order,customer,operation,requestId,expectedVersion,
            OrderReauthorizationTransactions.AbortRequesterType.CUSTOMER,OrderReauthorizationTransactions.AbortReasonCode.CUSTOMER_REQUEST));
        if(!List.of(OrderReauthorizationTransactions.AbortResult.ACCEPTED,OrderReauthorizationTransactions.AbortResult.REPLAYED).contains(receipt.result()))
            throw new IllegalStateException("Withdrawal command conflicts or is no longer allowed");
        try{finishIfProven(order,customer,operation);}
        catch(PaymentGatewayException|IllegalArgumentException|IllegalStateException|org.springframework.dao.DataAccessException|TransactionException stopped){
            // Intent/STARTED remain durable. No financial retry, inferred result, reservation release or exception payload logging.
        }
    }
    private void finishIfProven(long order,long customer,UUID operation){
        new RequestedAbortProgression(results,gateway,manager,clock).advance(order,customer,operation);
    }
}
