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

        if (paymentAmount != authorizationAmount) {
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
}
