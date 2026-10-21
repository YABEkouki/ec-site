package com.example.ecsite.service.payment;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import com.example.ecsite.entity.*;

/** Validates business intent against complete locked history. Never writes inferred legacy purpose.
 * A match is not an external authorization-validity or POST permission check. */
public final class PaymentPurposeResolver {
    public boolean matches(Order order, Payment payment, PaymentTransaction operation,
            PaymentOperationSnapshot snapshot, List<Payment> payments, List<PaymentTransaction> history) {
        return matches(order,payment,operation,snapshot,payments,history,Set.of());
    }
    public boolean matches(Order order, Payment payment, PaymentTransaction operation,
            PaymentOperationSnapshot snapshot, List<Payment> payments, List<PaymentTransaction> history,
            Set<UUID> completedChanges) {
        return matches(order,payment,operation,snapshot,payments,history,completedChanges,Set.of());
    }
    public boolean matches(Order order, Payment payment, PaymentTransaction operation,
            PaymentOperationSnapshot snapshot, List<Payment> payments, List<PaymentTransaction> history,
            Set<UUID> completedChanges, Set<UUID> abortedChanges) {
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
        Long historyPaymentId=order.getActivePaymentId();
        if(historyPaymentId==null && terminalCancellationReplay(order,payment,operation,snapshot,payments,history))
            historyPaymentId=payment.getId();
        if(!usableHistory(order,payments,history,completedChanges,abortedChanges,historyPaymentId)) return false;
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
        return order.getStatus()==OrderStatus.SHIPPED
                && own.stream().filter(t->!failedOrdinaryCancellation(order,payment,t)).count()==2
                && new CaptureShipmentSnapshotMatcher().matches(order,payment,operation,snapshot);
    }
    private boolean failedOrdinaryCancellation(Order order, Payment payment, PaymentTransaction transaction) {
        return transaction.getTransactionType()==PaymentTransactionType.CANCEL
                && transaction.getStatus()==PaymentTransactionStatus.FAILED
                && transaction.getOrderChangeOperationId()==null
                && (transaction.getPurpose()==null || transaction.getPurpose()==PaymentTransactionPurpose.ORDER_CANCELLATION)
                && transaction.getAmount()==payment.getAmount()
                && transaction.getOrderContentRevision()<=order.getContentRevision()
                && (transaction.getProviderTransactionId()==null
                    || Objects.equals(transaction.getProviderTransactionId(),payment.getProviderPaymentId()))
                && ((transaction.getInitiatorType()==PaymentTransactionInitiatorType.USER
                        && Objects.equals(transaction.getInitiatorId(),order.getUserId()))
                    || (transaction.getInitiatorType()==PaymentTransactionInitiatorType.ADMIN
                        && transaction.getInitiatorId()!=null && transaction.getInitiatorId()>0));
    }

    /** Persisted purpose evidence only. Does not establish current eligibility or permit mutation.
     * Caller first checks every immutable snapshot identity/request field under the existing locks. */
    boolean matchesResultIdentity(Order order, Payment payment, PaymentTransaction operation,
            PaymentOperationSnapshot snapshot, List<PaymentTransaction> history) {
        if (operation.getPurpose()!=snapshot.purpose() || operation.getOrderChangeOperationId()!=null) return false;
        var expected=switch(operation.getTransactionType()) {
            case AUTHORIZE -> PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION;
            case CANCEL -> PaymentTransactionPurpose.ORDER_CANCELLATION;
            default -> null;
        };
        if (expected==null || (operation.getPurpose()!=null && operation.getPurpose()!=expected)) return false;
        var own=history.stream().filter(t->Objects.equals(t.getPayment().getId(),payment.getId())).toList();
        if (own.stream().anyMatch(t->t.getOrderChangeOperationId()!=null
                || (t.getTransactionType()!=PaymentTransactionType.AUTHORIZE
                    && t.getTransactionType()!=PaymentTransactionType.CAPTURE
                    && t.getTransactionType()!=PaymentTransactionType.CANCEL)
                || (t.getPurpose()!=null && !new PaymentPurposePolicy().isUsableInStage1A(
                        t.getTransactionType().name(),t.getPurpose())))) return false;
        if (own.stream().filter(t->t.getTransactionType()==PaymentTransactionType.CAPTURE).count()>1
                || own.stream().filter(t->t.getStatus()==PaymentTransactionStatus.PENDING).count()>1
                || (own.stream().anyMatch(t->t.getTransactionType()==PaymentTransactionType.CANCEL
                            && t.getStatus()==PaymentTransactionStatus.SUCCEEDED)
                    && own.stream().anyMatch(t->t.getTransactionType()==PaymentTransactionType.CAPTURE))) return false;
        var auth=own.stream().filter(t->t.getTransactionType()==PaymentTransactionType.AUTHORIZE).toList();
        if (auth.size()!=1 || auth.getFirst().getAmount()!=payment.getAmount()
                || operation.getAmount()!=payment.getAmount()) return false;
        if (own.stream().anyMatch(t->t.getAmount()<=0 || t.getOrderContentRevision()<auth.getFirst().getOrderContentRevision()
                || t.getOrderContentRevision()>order.getContentRevision()
                || (t.getProviderTransactionId()!=null
                    && !Objects.equals(t.getProviderTransactionId(),payment.getProviderPaymentId()))
                || (t.getTransactionType()==PaymentTransactionType.CANCEL && t.getAmount()!=payment.getAmount())
                || (t.getTransactionType()==PaymentTransactionType.CAPTURE && t.getAmount()>payment.getAmount()))) return false;
        if (operation.getProviderTransactionId()!=null
                && !Objects.equals(operation.getProviderTransactionId(),payment.getProviderPaymentId())) return false;
        if (auth.getFirst().getStatus()==PaymentTransactionStatus.SUCCEEDED
                && !Objects.equals(auth.getFirst().getProviderTransactionId(),payment.getProviderPaymentId())) return false;
        if (expected==PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION)
            return Objects.equals(auth.getFirst().getId(),operation.getId());
        var authorization=auth.getFirst();
        boolean initial=authorization.getOrderChangeOperationId()==null
                && (authorization.getPurpose()==null
                    || authorization.getPurpose()==PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
        // A changed-generation cancellation still needs the full completed-operation proof in matches().
        if (!initial) return false;
        return authorization.getStatus()==PaymentTransactionStatus.SUCCEEDED
                && Objects.equals(authorization.getProviderTransactionId(),payment.getProviderPaymentId())
                && operation.getOrderContentRevision()>=authorization.getOrderContentRevision()
                && ((operation.getInitiatorType()==PaymentTransactionInitiatorType.USER
                        && Objects.equals(operation.getInitiatorId(),order.getUserId()))
                    || (operation.getInitiatorType()==PaymentTransactionInitiatorType.ADMIN
                        && operation.getInitiatorId()!=null && operation.getInitiatorId()>0));
    }

    /** Completed changes grant supporting evidence only; ordinary entrypoints still enforce their own purpose. */
    static boolean usableHistory(Order order, List<Payment> payments, List<PaymentTransaction> history,
            Set<UUID> completedChanges) {
        return usableHistory(order,payments,history,completedChanges,Set.of());
    }
    static boolean usableHistory(Order order, List<Payment> payments, List<PaymentTransaction> history,
            Set<UUID> completedChanges, Set<UUID> abortedChanges) {
        return usableHistory(order,payments,history,completedChanges,abortedChanges,order.getActivePaymentId());
    }
    private static boolean usableHistory(Order order, List<Payment> payments, List<PaymentTransaction> history,
            Set<UUID> completedChanges, Set<UUID> abortedChanges, Long evidencePaymentId) {
        if(completedChanges==null) completedChanges=Set.of();
        if(abortedChanges==null) abortedChanges=Set.of();
        for(var transaction:history) {
            var purpose=transaction.getPurpose();
            if(purpose==null || new PaymentPurposePolicy().isUsableInStage1A(
                    transaction.getTransactionType().name(),purpose)) continue;
            if(evidencePaymentId!=null && transaction.getOrderChangeOperationId()!=null
                    && abortedChanges.contains(transaction.getOrderChangeOperationId())
                    && !Objects.equals(evidencePaymentId,transaction.getPayment().getId())
                    && abortedGeneration(transaction.getPayment(),history,transaction.getOrderChangeOperationId())) continue;
            if(evidencePaymentId==null || transaction.getOrderChangeOperationId()==null
                    || !completedChanges.contains(transaction.getOrderChangeOperationId())
                    || transaction.getStatus()!=PaymentTransactionStatus.SUCCEEDED) return false;
            var payment=payments.stream().filter(p->Objects.equals(p.getId(),transaction.getPayment().getId())).findFirst().orElse(null);
            if(payment==null || payment.getOrder()==null || !Objects.equals(payment.getOrder().getId(),order.getId())
                    || payment.getProvider()!=PaymentProvider.PAYJP || payment.getPaymentMethod()!=PaymentMethod.CARD
                    || payment.getAmount()!=transaction.getAmount()
                    || !Objects.equals(payment.getProviderPaymentId(),transaction.getProviderTransactionId())
                    || payment.getProviderPaymentId()==null || payment.getProviderPaymentId().isBlank()) return false;
            boolean active=Objects.equals(evidencePaymentId,payment.getId());
            if(purpose==PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION
                    && transaction.getTransactionType()==PaymentTransactionType.AUTHORIZE) {
                if(history.stream().filter(t->Objects.equals(t.getPayment().getId(),payment.getId())
                        && t.getTransactionType()==PaymentTransactionType.AUTHORIZE).count()!=1
                        || (!active && !closedGeneration(payment,history,completedChanges))) return false;
            } else if(purpose==PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL
                    && transaction.getTransactionType()==PaymentTransactionType.CANCEL) {
                if(active || !closedGeneration(payment,history,completedChanges)) return false;
            } else return false;
        }
        return true;
    }
    /** The cleared binding is reconstructed only for an exact, already settled ordinary cancellation. */
    private static boolean terminalCancellationReplay(Order order,Payment payment,PaymentTransaction operation,
            PaymentOperationSnapshot snapshot,List<Payment> payments,List<PaymentTransaction> history) {
        return CaptureShipmentPolicy.validInput(order,payments,history)
                && order.getStatus()==OrderStatus.CANCELLED && order.getActivePaymentId()==null
                && payment.getStatus()==PaymentStatus.CANCELLED
                && operation.getTransactionType()==PaymentTransactionType.CANCEL
                && (operation.getPurpose()==null || operation.getPurpose()==PaymentTransactionPurpose.ORDER_CANCELLATION)
                && operation.getStatus()==PaymentTransactionStatus.SUCCEEDED && operation.getCompletedAt()!=null
                && operation.getOrderChangeOperationId()==null
                && Objects.equals(operation.getProviderTransactionId(),payment.getProviderPaymentId())
                && Objects.equals(snapshot.idempotencyKey(),operation.getIdempotencyKey())
                && snapshot.amount()==operation.getAmount()
                && snapshot.orderContentRevision()==operation.getOrderContentRevision()
                && snapshot.provider()==payment.getProvider()
                && Objects.equals(snapshot.providerPaymentId(),payment.getProviderPaymentId())
                && snapshot.initiatorType()==operation.getInitiatorType()
                && Objects.equals(snapshot.initiatorId(),operation.getInitiatorId())
                && Objects.equals(snapshot.initiatorUsername(),operation.getInitiatorUsername())
                && Objects.equals(snapshot.internalNote(),operation.getInternalNote());
    }
    private static boolean abortedGeneration(Payment payment,List<PaymentTransaction> history,UUID operationId) {
        if(payment.getStatus()!=PaymentStatus.FAILED && payment.getStatus()!=PaymentStatus.CANCELLED) return false;
        var own=history.stream().filter(t->Objects.equals(t.getPayment().getId(),payment.getId())).toList();
        if(own.stream().anyMatch(t->!Objects.equals(t.getOrderChangeOperationId(),operationId)
                || t.getStatus()==PaymentTransactionStatus.PENDING || t.getAmount()!=payment.getAmount())) return false;
        var auth=own.stream().filter(t->t.getTransactionType()==PaymentTransactionType.AUTHORIZE
                && t.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION).toList();
        if(auth.size()!=1) return false;
        if(own.size()==1) return auth.getFirst().getStatus()==PaymentTransactionStatus.FAILED;
        var abort=own.stream().filter(t->t.getTransactionType()==PaymentTransactionType.CANCEL
                && t.getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_NEW_AUTH_ABORT).toList();
        return own.size()==2 && payment.getStatus()==PaymentStatus.CANCELLED
                && auth.getFirst().getStatus()==PaymentTransactionStatus.SUCCEEDED
                && abort.size()==1 && abort.getFirst().getStatus()==PaymentTransactionStatus.SUCCEEDED
                && payment.getProviderPaymentId()!=null && !payment.getProviderPaymentId().isBlank()
                && Objects.equals(payment.getProviderPaymentId(),auth.getFirst().getProviderTransactionId())
                && Objects.equals(payment.getProviderPaymentId(),abort.getFirst().getProviderTransactionId());
    }
    private static boolean closedGeneration(Payment payment,List<PaymentTransaction> history,Set<UUID> completedChanges) {
        var own=history.stream().filter(t->Objects.equals(t.getPayment().getId(),payment.getId())).toList();
        if(payment.getStatus()!=PaymentStatus.CANCELLED || own.size()!=2) return false;
        var auth=own.stream().filter(t->t.getTransactionType()==PaymentTransactionType.AUTHORIZE).toList();
        var cancel=own.stream().filter(t->t.getTransactionType()==PaymentTransactionType.CANCEL).toList();
        return auth.size()==1 && cancel.size()==1 && auth.getFirst().getStatus()==PaymentTransactionStatus.SUCCEEDED
                && cancel.getFirst().getStatus()==PaymentTransactionStatus.SUCCEEDED
                && cancel.getFirst().getPurpose()==PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL
                && cancel.getFirst().getOrderChangeOperationId()!=null
                && completedChanges.contains(cancel.getFirst().getOrderChangeOperationId())
                && auth.getFirst().getAmount()==payment.getAmount()
                && Objects.equals(auth.getFirst().getProviderTransactionId(),payment.getProviderPaymentId());
    }

}
