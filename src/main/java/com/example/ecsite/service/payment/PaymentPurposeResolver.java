package com.example.ecsite.service.payment;

import java.util.List;
import java.util.Objects;
import com.example.ecsite.entity.*;

/** Validates business intent against complete locked history. Never writes inferred legacy purpose.
 * A match is not an external authorization-validity or POST permission check. */
public final class PaymentPurposeResolver {
    public boolean matches(Order order, Payment payment, PaymentTransaction operation,
            PaymentOperationSnapshot snapshot, List<Payment> payments, List<PaymentTransaction> history) {
        if (order == null || payment == null || operation == null || snapshot == null
                || payment.getOrder() == null || operation.getPayment() == null
                || !Objects.equals(order.getId(),payment.getOrder().getId())
                || !Objects.equals(payment.getId(),operation.getPayment().getId())
                || !Objects.equals(snapshot.orderId(),order.getId())
                || !Objects.equals(snapshot.paymentId(),payment.getId())
                || !Objects.equals(snapshot.transactionId(),operation.getId())
                || snapshot.type()!=operation.getTransactionType() || snapshot.purpose()!=operation.getPurpose()
                || payment.getProvider()!=PaymentProvider.PAYJP || payment.getPaymentMethod()!=PaymentMethod.CARD
                || payments.stream().filter(p->Objects.equals(p.getId(),payment.getId())).count()!=1
                || history.stream().filter(t->Objects.equals(t.getId(),operation.getId())).count()!=1
                || (order.getActivePaymentId()!=null && !order.getActivePaymentId().equals(payment.getId()))) return false;
        PaymentTransactionPurpose expected = switch(operation.getTransactionType()) {
            case AUTHORIZE -> PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION;
            case CANCEL -> PaymentTransactionPurpose.ORDER_CANCELLATION;
            case CAPTURE -> PaymentTransactionPurpose.SHIPMENT_CAPTURE;
            default -> null;
        };
        if(expected==null || (operation.getPurpose()!=null && operation.getPurpose()!=expected)) return false;
        if(payments.stream().anyMatch(p -> !Objects.equals(p.getOrder().getId(),order.getId())
                || (!Objects.equals(p.getId(),payment.getId()) && p.getStatus()!=PaymentStatus.FAILED && p.getStatus()!=PaymentStatus.CANCELLED))) return false;
        // A terminal Payment flag does not settle a still-pending financial operation.
        if(history.stream().anyMatch(t -> t.getStatus()==PaymentTransactionStatus.PENDING
                && !Objects.equals(t.getId(),operation.getId()))) return false;
        var own=history.stream().filter(t->Objects.equals(t.getPayment().getId(),payment.getId())).toList();
        if(own.stream().anyMatch(t->t.getPurpose()!=null && !new PaymentPurposePolicy().isUsableInStage1A(
                t.getTransactionType().name(),t.getPurpose()))) return false;
        var auth=own.stream().filter(t->t.getTransactionType()==PaymentTransactionType.AUTHORIZE).toList();
        if(auth.size()!=1 || auth.getFirst().getAmount()!=payment.getAmount()) return false;
        var authorization=auth.getFirst();
        if(expected==PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION) {
            if(own.size()!=1 || (order.getStatus()!=OrderStatus.ORDERED && order.getStatus()!=OrderStatus.CANCELLED)) return false;
            if(operation.getStatus()==PaymentTransactionStatus.SUCCEEDED)
                return new PaymentConsistencyEvaluator().evaluate(order,payment,authorization).canCapture();
            return order.getTotalAmount()==payment.getAmount()
                    && order.getContentRevision()==operation.getOrderContentRevision();
        }
        if(payment.getProviderPaymentId()==null || payment.getProviderPaymentId().isBlank()
                || !Objects.equals(authorization.getProviderTransactionId(),payment.getProviderPaymentId())
                || authorization.getStatus()!=PaymentTransactionStatus.SUCCEEDED
                || !new PaymentConsistencyEvaluator().evaluate(order,payment,authorization).canCapture()) return false;
        if(expected==PaymentTransactionPurpose.ORDER_CANCELLATION) {
            return (order.getStatus()==OrderStatus.ORDERED || order.getStatus()==OrderStatus.CANCELLED)
                    && own.size()==2 && operation.getAmount()==payment.getAmount()
                    && operation.getOrderContentRevision()==order.getContentRevision()
                    && ((operation.getInitiatorType()==PaymentTransactionInitiatorType.USER
                            && Objects.equals(order.getUserId(),operation.getInitiatorId()))
                        || (operation.getInitiatorType()==PaymentTransactionInitiatorType.ADMIN
                            && operation.getInitiatorId()!=null && operation.getInitiatorId()>0));
        }
        return order.getStatus()==OrderStatus.SHIPPED && own.size()==2
                && new CaptureShipmentSnapshotMatcher().matches(order,payment,operation,snapshot);
    }
}
