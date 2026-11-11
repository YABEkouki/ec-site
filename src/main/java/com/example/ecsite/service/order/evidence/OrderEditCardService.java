package com.example.ecsite.service.order.evidence;

import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.payment.*;
import com.example.ecsite.service.orderchange.OrderReauthorizationTransactions;
import com.example.ecsite.service.orderchange.OrderChangeBrowserService.Card;

/** Scoped adapter to the existing first-confirm marker and Widget. GET transport never holds DB locks. */
@Service
public class OrderEditCardService {
    private final OrderReauthorizationTransactions transactions;
    private final PaymentGateway gateway;
    private final PayJpProperties provider;
    private final URI origin;
    public OrderEditCardService(OrderReauthorizationTransactions transactions,PaymentGateway gateway,PayJpProperties provider,
            @Value("${app.base-url}") String baseUrl){
        this.transactions=transactions;this.gateway=gateway;this.provider=provider;origin=URI.create(baseUrl);
        if(origin.getHost()==null||origin.getRawUserInfo()!=null||origin.getRawQuery()!=null||origin.getRawFragment()!=null
            ||!(origin.getPath().isEmpty()||origin.getPath().equals("/"))
            ||!("https".equals(origin.getScheme())||("http".equals(origin.getScheme())&&"localhost".equals(origin.getHost()))))
            throw new IllegalArgumentException("A trusted root application origin is required");
    }
    public Optional<Card> start(long order,long customer,UUID admission){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Browser transport requires no caller transaction");
        try{
            var prepared=transactions.prepareOrderEditBrowser(order,customer,admission);if(prepared.isEmpty())return Optional.empty();
            var target=prepared.get();long started=System.nanoTime();
            var observed=gateway.observeOrderChangeFlow(target.flow());
            if(observed==null||!target.flow().equals(observed.id())||!"requires_payment_method".equals(observed.status()))return Optional.empty();
            var recovered=gateway.retrieveAuthorization(target.flow());
            if(recovered==null||recovered.result()==null||recovered.result().status()!=AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD
                ||(recovered.result().providerTransactionId()!=null&&!target.flow().equals(recovered.result().providerTransactionId()))||recovered.clientSecret()==null||recovered.clientSecret().isBlank()
                ||provider.publicKey()==null||provider.publicKey().isBlank())return Optional.empty();
            if(!transactions.issueOrderEditBrowser(target,observed,started))return Optional.empty();
            return Optional.of(new Card(provider.publicKey(),recovered.clientSecret(),origin.resolve(
                "/orders/"+order+"/edit/"+admission+"/payment/return").toString()));
        }catch(RuntimeException unavailable){return Optional.empty();} // No guessed rollback or automatic reissue.
    }
}
