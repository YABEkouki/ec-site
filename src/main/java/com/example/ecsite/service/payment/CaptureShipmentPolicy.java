package com.example.ecsite.service.payment;

import java.util.*;
import org.springframework.stereotype.Component;
import com.example.ecsite.entity.*;

/**
 * Pure classification, not an execution permit: no queries, locks, clocks, updates or gateway calls.
 * Caller supplies COMPLETE persisted payment/history sets for one order, refreshed under Order -> Payment locks.
 * This class cannot discover omitted database rows. Expected snapshot is required for shipped operations.
 */
@Component
public class CaptureShipmentPolicy {
    private final PaymentConsistencyEvaluator evaluator;
    private final CaptureShipmentSnapshotMatcher matcher;
    public CaptureShipmentPolicy(PaymentConsistencyEvaluator evaluator, CaptureShipmentSnapshotMatcher matcher) {
        this.evaluator=evaluator; this.matcher=matcher;
    }
    public enum Disposition { NEW, CONTINUE, ALREADY_APPLIED, RECOVERY_REQUIRED }
    public enum Reason { NONE, INVALID_INPUT, INVALID_PREPARATION, INVALID_SHIPMENT, AMBIGUOUS_PAYMENT,
        INVALID_PAYMENT, AUTHORIZATION_MISMATCH, CAPTURE_HISTORY, PENDING_CONFLICT, AMBIGUOUS_CAPTURE,
        SNAPSHOT_MISMATCH, INCONSISTENT_STATE }
    public record Decision(Disposition disposition, Reason reason, Long paymentId, Long captureTransactionId) {}

    public Decision classify(Order order, List<Payment> payments, List<PaymentTransaction> history, PaymentOperationSnapshot expected) {
        if(!validInput(order,payments,history)) return recovery(Reason.INVALID_INPUT);
        if(history.stream().anyMatch(t -> t.getPurpose()!=null && !new PaymentPurposePolicy()
                .isUsableInStage1A(t.getTransactionType().name(),t.getPurpose()))) return recovery(Reason.INVALID_INPUT);
        if(!order.hasCompleteShippingPreparation()) return recovery(Reason.INVALID_PREPARATION);
        if(order.getStatus()!=OrderStatus.ORDERED && order.getStatus()!=OrderStatus.SHIPPED) return recovery(Reason.INVALID_SHIPMENT);
        var active=payments.stream().filter(p -> p.getStatus()!=PaymentStatus.FAILED && p.getStatus()!=PaymentStatus.CANCELLED).toList();
        if(active.size()!=1) return recovery(Reason.AMBIGUOUS_PAYMENT);
        Payment payment=active.getFirst();
        if(order.getActivePaymentId()!=null && !Objects.equals(order.getActivePaymentId(),payment.getId()))
            return recovery(Reason.INVALID_PAYMENT);
        if(payment.getProvider()!=PaymentProvider.PAYJP || payment.getPaymentMethod()!=PaymentMethod.CARD
                || payment.getProviderPaymentId()==null || payment.getProviderPaymentId().isBlank()) return recovery(Reason.INVALID_PAYMENT);
        if(order.getStatus()==OrderStatus.ORDERED) {
            if(expected!=null || order.getPaidAt()!=null || order.getShippedAt()!=null || order.getShippedByAccountId()!=null
                    || order.getShippedByUsername()!=null || order.getShipmentCapturePaymentId()!=null) return recovery(Reason.INVALID_SHIPMENT);
            // Fail closed on any prior capture, including failed or legacy operations; never select the latest.
            if(history.stream().anyMatch(t -> t.getTransactionType()==PaymentTransactionType.CAPTURE)) return recovery(Reason.CAPTURE_HISTORY);
            if(history.stream().anyMatch(t -> t.getStatus()==PaymentTransactionStatus.PENDING)) return recovery(Reason.PENDING_CONFLICT);
            if(payment.getStatus()!=PaymentStatus.AUTHORIZED) return recovery(Reason.INVALID_PAYMENT);
            if(!captureConsistent(order,payment,history)) return recovery(Reason.AUTHORIZATION_MISMATCH);
            return new Decision(Disposition.NEW,Reason.NONE,payment.getId(),null);
        }
        if(!Objects.equals(order.getShipmentCapturePaymentId(),payment.getId()) || order.getShippedAt()==null
                || order.getShippedByAccountId()==null || order.getShippedByAccountId()<=0
                || order.getShippedByUsername()==null || order.getShippedByUsername().isBlank()
                || order.getShippedByUsername().length()>100) return recovery(Reason.INVALID_SHIPMENT);
        var candidates=history.stream().filter(t -> t.getTransactionType()==PaymentTransactionType.CAPTURE
                && Objects.equals(t.getPayment().getId(),payment.getId())).toList();
        if(candidates.size()!=1) return recovery(Reason.AMBIGUOUS_CAPTURE);
        PaymentTransaction capture=candidates.getFirst();
        if(!matcher.matches(order,payment,capture,expected)) return recovery(Reason.SNAPSHOT_MISMATCH);
        if(history.stream().anyMatch(t -> t.getStatus()==PaymentTransactionStatus.PENDING && !Objects.equals(t.getId(),capture.getId())))
            return recovery(Reason.PENDING_CONFLICT);
        if(!captureConsistent(order,payment,history)) return recovery(Reason.AUTHORIZATION_MISMATCH);
        if(capture.getStatus()==PaymentTransactionStatus.PENDING && payment.getStatus()==PaymentStatus.AUTHORIZED
                && order.getPaidAt()==null && capture.getCompletedAt()==null
                && (capture.getProviderTransactionId()==null || capture.getProviderTransactionId().equals(payment.getProviderPaymentId()))) {
            return new Decision(Disposition.CONTINUE,Reason.NONE,payment.getId(),capture.getId());
        }
        if(capture.getStatus()==PaymentTransactionStatus.SUCCEEDED && payment.getStatus()==PaymentStatus.CAPTURED
                && order.getPaidAt()!=null && !order.getPaidAt().isBefore(order.getShippedAt())
                && capture.getCompletedAt()!=null && !capture.getCompletedAt().isBefore(order.getShippedAt())
                && Objects.equals(capture.getProviderTransactionId(),payment.getProviderPaymentId())) {
            return new Decision(Disposition.ALREADY_APPLIED,Reason.NONE,payment.getId(),capture.getId());
        }
        return recovery(Reason.INCONSISTENT_STATE);
    }
    private boolean captureConsistent(Order order, Payment payment, List<PaymentTransaction> history) {
        var authorizations=history.stream().filter(t -> Objects.equals(t.getPayment().getId(),payment.getId())
                && t.getTransactionType()==PaymentTransactionType.AUTHORIZE && t.getStatus()==PaymentTransactionStatus.SUCCEEDED).toList();
        if(authorizations.size()!=1) return false;
        var authorization=authorizations.getFirst();
        return Objects.equals(authorization.getProviderTransactionId(),payment.getProviderPaymentId())
                && evaluator.evaluate(order,payment,authorization).canCapture();
    }
    private boolean validInput(Order order,List<Payment> payments,List<PaymentTransaction> history) {
        if(order==null || !positive(order.getId()) || order.getTotalAmount()<=0 || order.getContentRevision()<0 || payments==null || history==null) return false;
        Set<Long> paymentIds=new HashSet<>(),transactionIds=new HashSet<>(); Set<String> keys=new HashSet<>();
        for(Payment payment:payments) {
            if(payment==null || !positive(payment.getId()) || !paymentIds.add(payment.getId()) || payment.getOrder()==null
                    || !Objects.equals(payment.getOrder().getId(),order.getId()) || payment.getStatus()==null || payment.getAmount()<=0) return false;
        }
        for(PaymentTransaction transaction:history) {
            if(transaction==null || !positive(transaction.getId()) || !transactionIds.add(transaction.getId())
                    || transaction.getPayment()==null || !paymentIds.contains(transaction.getPayment().getId())
                    || transaction.getPayment().getOrder()==null || !Objects.equals(transaction.getPayment().getOrder().getId(),order.getId())
                    || transaction.getTransactionType()==null || transaction.getStatus()==null || transaction.getAmount()<=0
                    || transaction.getOrderContentRevision()<0 || transaction.getCreatedAt()==null
                    || transaction.getIdempotencyKey()==null || transaction.getIdempotencyKey().isBlank()
                    || transaction.getIdempotencyKey().length()>100 || !keys.add(transaction.getIdempotencyKey())) return false;
        }
        return true;
    }
    private boolean positive(Long id) { return id!=null && id>0; }
    private Decision recovery(Reason reason) { return new Decision(Disposition.RECOVERY_REQUIRED,reason,null,null); }
}
