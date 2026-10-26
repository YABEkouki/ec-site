package com.example.ecsite.service.payment;

import java.util.Objects;
import org.springframework.stereotype.Component;
import com.example.ecsite.entity.*;

/** Identity/request-value matching only. Snapshot status is mutable, not operation identity. */
@Component
public class CaptureShipmentSnapshotMatcher {
    /**
     * expected must come from a trusted operation snapshot, never browser fields.
     * Capture-request provider ID is not separately persisted on PaymentTransaction:
     * matching a freshly rebuilt snapshot cannot prove the original request value.
     */
    public boolean matches(Order order, Payment payment, PaymentTransaction transaction, PaymentOperationSnapshot expected) {
        if(order==null || payment==null || transaction==null || expected==null
                || payment.getOrder()==null || transaction.getPayment()==null) return false;
        return positive(order.getId()) && positive(payment.getId()) && positive(transaction.getId())
                && Objects.equals(payment.getOrder().getId(),order.getId())
                && Objects.equals(transaction.getPayment().getId(),payment.getId())
                && Objects.equals(order.getShipmentCapturePaymentId(),payment.getId())
                && Objects.equals(expected.orderId(),order.getId()) && Objects.equals(expected.paymentId(),payment.getId())
                && Objects.equals(expected.transactionId(),transaction.getId())
                && expected.purpose()==transaction.getPurpose()
                && (transaction.getPurpose()==null || transaction.getPurpose()==PaymentTransactionPurpose.SHIPMENT_CAPTURE)
                && expected.type()==PaymentTransactionType.CAPTURE && transaction.getTransactionType()==PaymentTransactionType.CAPTURE
                && validKey(transaction.getIdempotencyKey()) && Objects.equals(expected.idempotencyKey(),transaction.getIdempotencyKey())
                && payment.getProvider()==PaymentProvider.PAYJP && payment.getPaymentMethod()==PaymentMethod.CARD
                && expected.provider()==payment.getProvider() && nonblank(payment.getProviderPaymentId())
                && Objects.equals(expected.providerPaymentId(),payment.getProviderPaymentId())
                && transaction.getAmount()>0 && transaction.getAmount()==order.getTotalAmount() && expected.amount()==transaction.getAmount()
                && transaction.getOrderContentRevision()==order.getContentRevision() && expected.orderContentRevision()==transaction.getOrderContentRevision()
                && transaction.getInitiatorType()==PaymentTransactionInitiatorType.ADMIN && expected.initiatorType()==transaction.getInitiatorType()
                && positive(transaction.getInitiatorId()) && Objects.equals(expected.initiatorId(),transaction.getInitiatorId())
                && validActorName(transaction.getInitiatorUsername()) && Objects.equals(expected.initiatorUsername(),transaction.getInitiatorUsername())
                && Objects.equals(order.getShippedByAccountId(),transaction.getInitiatorId())
                && Objects.equals(order.getShippedByUsername(),transaction.getInitiatorUsername())
                && Objects.equals(expected.internalNote(),transaction.getInternalNote());
    }
    private boolean positive(Long id) { return id!=null && id>0; }
    private boolean nonblank(String value) { return value!=null && !value.isBlank(); }
    private boolean validKey(String value) { return nonblank(value) && value.length()<=100; }
    private boolean validActorName(String value) { return nonblank(value) && value.length()<=100; }
}
