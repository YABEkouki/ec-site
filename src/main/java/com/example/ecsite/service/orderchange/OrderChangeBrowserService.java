package com.example.ecsite.service.orderchange;

import java.util.*;
import java.net.URI;
import com.example.ecsite.payment.*;
import com.example.ecsite.config.PayJpProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Customer boundary. Ownership is checked before any provider call; HTTP never runs in a DB transaction. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class OrderChangeBrowserService {
    public record Card(String publicKey,String clientSecret,String returnUrl){
        @Override public String toString(){return "OrderChangeCard[redacted]";}
    }
    private final OrderIncreaseService increases;
    private final OrderReauthorizationService financial;
    private final OrderReauthorizationTransactions results;
    private final PaymentGateway gateway;
    private final PayJpProperties provider;
    private final URI origin;
    public OrderChangeBrowserService(OrderIncreaseService increases,OrderReauthorizationService financial,
            OrderReauthorizationTransactions results,PaymentGateway gateway,PayJpProperties provider,
            @Value("${app.base-url}") String baseUrl){
        this.increases=increases;this.financial=financial;this.results=results;this.gateway=gateway;this.provider=provider;
        this.origin=URI.create(baseUrl);
        if(origin.getHost()==null || origin.getRawUserInfo()!=null || origin.getRawQuery()!=null || origin.getRawFragment()!=null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                || !("https".equals(origin.getScheme()) || ("http".equals(origin.getScheme()) && "localhost".equals(origin.getHost()))))
            throw new IllegalArgumentException("A trusted root application origin is required");
    }
    public Optional<Card> start(long order,long customer,UUID operation){
        increases.status(order,customer,operation);
        increases.expireReserved(order,operation);
        var view=financial.advance(order,operation);
        if(!"AUTHORIZING".equals(view.state()) || view.newFlow()==null)return Optional.empty();
        var observed=gateway.observeOrderChangeFlow(view.newFlow());
        view=results.authorization(order,operation,observed);
        if(!"AUTHORIZING".equals(view.state()) || !"requires_payment_method".equals(observed.status()))return Optional.empty();
        var recovered=gateway.retrieveAuthorization(view.newFlow());
        if(recovered==null || recovered.result()==null || recovered.result().status()!=AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD
                || (recovered.result().providerTransactionId()!=null && !view.newFlow().equals(recovered.result().providerTransactionId()))
                || recovered.clientSecret()==null || recovered.clientSecret().isBlank())return Optional.empty();
        if(!results.issueBrowserConfirmation(order,operation,observed))return Optional.empty();
        return Optional.of(new Card(provider.publicKey(),recovered.clientSecret(),origin.resolve(
            "/orders/"+order+"/increase/"+operation+"/payment/return").toString()));
    }
    public void continueChange(long order,long customer,UUID operation){
        increases.status(order,customer,operation);increases.expireReserved(order,operation);financial.advance(order,operation);
    }
    public void observe(long order,long customer,UUID operation){
        var op=increases.status(order,customer,operation);
        if(List.of("RESERVED","COMPLETED","ABORTED","MANUAL_REVIEW").contains(op.state()))return;
        financial.reconcile(order,operation); // GETs and safe result application only. Never CREATE/Cancel/confirm.
    }
}
