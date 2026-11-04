package com.example.ecsite.payment;

import java.util.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.service.order.evidence.OrderContentSnapshot;
import com.example.ecsite.service.order.evidence.OrderPaymentEvidence;

/** Opaque local content proof. It never authorizes a financial action or asserts provider freshness. */
public final class PaymentContentEvidence {
    public enum State { LEGACY, VERIFIED, BLOCKED }
    private final State state;
    private final OrderContentSnapshot snapshot;
    private final UUID epoch;
    private final Long head;
    private final OrderPaymentEvidence.Payment payment;
    private final OrderPaymentEvidence.Authorization authorization;
    PaymentContentEvidence(State state,OrderPaymentEvidence.Facts facts){
        this.state=Objects.requireNonNull(state);
        snapshot=facts==null?null:facts.current();epoch=facts==null||facts.epoch()==null?null:facts.epoch().id();head=facts==null?null:facts.headHistoryId();
        payment=state==State.VERIFIED?facts.payments().stream().filter(p->Objects.equals(facts.activePaymentId(),p.id())).findFirst().orElseThrow():null;
        authorization=state==State.VERIFIED?facts.authorizations().stream().filter(a->a.paymentId()==payment.id()).findFirst().orElseThrow():null;
    }
    public State state(){return state;}
    public boolean matchesOrder(Order order){
        return state==State.VERIFIED&&order!=null&&Objects.equals(order.getId(),snapshot.orderId())
            &&Objects.equals(order.getUserId(),snapshot.customerId())&&order.getContentRevision()==snapshot.contentRevision()
            &&order.getItemSubtotal()==snapshot.itemSubtotal()&&order.getChargeTotal()==snapshot.chargeTotal()
            &&order.getTaxAmount()==snapshot.taxAmount()&&order.getTotalAmount()==snapshot.totalAmount()
            &&Objects.equals(order.getCurrentEvidenceEpochId(),epoch)&&Objects.equals(order.getEvidenceHeadHistoryId(),head)
            &&Objects.equals(order.getActivePaymentId(),payment.id());
    }
    public boolean matches(Order order,Payment actual,PaymentTransaction auth){
        return matchesOrder(order)&&actual!=null&&auth!=null&&Objects.equals(actual.getId(),payment.id())
            &&actual.getOrder()!=null&&Objects.equals(actual.getOrder().getId(),snapshot.orderId())
            &&actual.getAmount()==payment.amount()&&actual.getProvider()!=null&&actual.getProvider().name().equals(payment.provider())
            &&actual.getPaymentMethod()!=null&&actual.getPaymentMethod().name().equals(payment.method())
            &&Objects.equals(actual.getProviderPaymentId(),payment.flowId())
            &&Objects.equals(auth.getId(),authorization.id())&&auth.getPayment()!=null&&Objects.equals(auth.getPayment().getId(),payment.id())
            &&auth.getTransactionType()==PaymentTransactionType.AUTHORIZE&&auth.getStatus()==PaymentTransactionStatus.SUCCEEDED
            &&auth.getAmount()==authorization.amount()&&auth.getOrderContentRevision()==authorization.revision()
            &&Objects.equals(auth.getProviderTransactionId(),authorization.flowId())
            &&Objects.equals(auth.getPurpose()==null?null:auth.getPurpose().name(),authorization.purpose())
            &&Objects.equals(auth.getOrderChangeOperationId(),authorization.operationId());
    }
    @Override public String toString(){return "PaymentContentEvidence["+state+"]";}
}
