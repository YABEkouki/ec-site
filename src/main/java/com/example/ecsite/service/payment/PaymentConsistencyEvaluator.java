package com.example.ecsite.service.payment;

import org.springframework.stereotype.Component;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentTransaction;

@Component
public class PaymentConsistencyEvaluator {

    public PaymentConsistency evaluate(
            Order order,
            Payment payment,
            PaymentTransaction authorizationTransaction) {

        int orderAmount = order.getTotalAmount();
        int paymentAmount = payment.getAmount();
        int authorizationAmount = authorizationTransaction.getAmount();
        int orderContentRevision = order.getContentRevision();
        int authorizationContentRevision =
                authorizationTransaction.getOrderContentRevision();

        PaymentConsistencyStatus status;

        if (order.getCurrentEvidenceEpochId()!=null||order.getEvidenceHeadHistoryId()!=null) {
            status=PaymentConsistencyStatus.EVIDENCE_REQUIRED;
        } else if (paymentAmount != authorizationAmount) {
            status = PaymentConsistencyStatus.AUTHORIZATION_MISMATCH;
        } else if (orderAmount < paymentAmount) {
            status = PaymentConsistencyStatus.AMOUNT_DECREASED;
        } else if (orderAmount > paymentAmount) {
            status = PaymentConsistencyStatus.AMOUNT_INCREASED;
        } else if (orderContentRevision != authorizationContentRevision) {
            status = PaymentConsistencyStatus.REVISION_MISMATCH;
        } else {
            status = PaymentConsistencyStatus.CONSISTENT;
        }

        return new PaymentConsistency(
                status,
                orderAmount,
                paymentAmount,
                authorizationAmount,
                orderContentRevision,
                authorizationContentRevision);
    }
    public PaymentConsistency evaluate(Order order,Payment payment,PaymentTransaction authorization,
            com.example.ecsite.payment.PaymentContentEvidence proof){
        if(proof==null)return evaluate(order,payment,authorization);
        if(proof!=null&&proof.state()==com.example.ecsite.payment.PaymentContentEvidence.State.LEGACY)
            return evaluate(order,payment,authorization);
        PaymentConsistencyStatus status=proof!=null&&proof.matches(order,payment,authorization)
            ?PaymentConsistencyStatus.EVIDENCE_VERIFIED:PaymentConsistencyStatus.EVIDENCE_REQUIRED;
        return new PaymentConsistency(status,order.getTotalAmount(),payment.getAmount(),authorization.getAmount(),
            order.getContentRevision(),authorization.getOrderContentRevision());
    }

}
