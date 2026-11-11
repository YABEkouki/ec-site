package com.example.ecsite.service.payment;

import java.util.Optional;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.PaymentTransactionRepository;
import com.example.ecsite.repository.PaymentRepository;

@Service
public class PaymentOperationSnapshotService {
    private final PaymentTransactionRepository transactions;
    private final PaymentRepository payments;
    public PaymentOperationSnapshotService(PaymentTransactionRepository transactions, PaymentRepository payments) {
        this.transactions = transactions;
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentOperationSnapshot> find(Long transactionId) {
        return transactions.findById(transactionId).map(PaymentOperationSnapshot::from);
    }

    /** Resolve once before GET; result application never selects a latest transaction. */
    @Transactional(readOnly = true)
    public PaymentOperationSnapshot authorization(Long orderId, Long paymentId) {
        var candidates = transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId).stream()
                .filter(t -> t.getTransactionType() == PaymentTransactionType.AUTHORIZE).toList();
        if (candidates.size() != 1) throw new IllegalStateException("与信操作が一意ではありません。");
        var snapshot = PaymentOperationSnapshot.from(candidates.getFirst());
        if (!snapshot.orderId().equals(orderId)) throw new IllegalArgumentException("注文と決済が一致しません。");
        if (snapshot.providerPaymentId() == null || snapshot.providerPaymentId().isBlank())
            throw new IllegalStateException("決済プロバイダーIDが設定されていません。");
        return snapshot;
    }

    /** All entity access is confined to this short read transaction. */
    @Transactional(readOnly = true)
    public Optional<PaymentOperationSnapshot> pendingForProvider(String providerPaymentId, PaymentTransactionType type) {
        if (providerPaymentId == null || providerPaymentId.isBlank()) return Optional.empty();
        Payment payment = payments.findByProviderAndProviderPaymentId(PaymentProvider.PAYJP, providerPaymentId).orElse(null);
        if (payment == null || payment.getPaymentMethod() != PaymentMethod.CARD) return Optional.empty();
        boolean eligible = type == PaymentTransactionType.AUTHORIZE
                ? payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.REQUIRES_ACTION
                : payment.getStatus() == PaymentStatus.AUTHORIZED;
        if (!eligible) return Optional.empty();
        var pending = transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId()).stream()
                .filter(t -> t.getTransactionType() == type && t.getStatus() == PaymentTransactionStatus.PENDING).toList();
        if (pending.size() > 1) throw new IllegalStateException("処理中の決済操作が一意ではありません。");
        return pending.stream().findFirst().map(t -> PaymentOperationSnapshot.from(payment, t));
    }
    /** Non-secret browser target. Read-only hint; result application still revalidates under locks. */
    public record CheckoutTarget(long orderId,long paymentId,long transactionId) {
        public String key(){return orderId+":"+paymentId+":"+transactionId;}
    }
    @Transactional(readOnly = true)
    public Optional<CheckoutTarget> checkoutTarget(long orderId,long paymentId,long customerId) {
        var payment=payments.findById(paymentId).orElse(null);
        if(payment==null)return Optional.empty();
        var order=payment.getOrder();
        if(!Objects.equals(order.getId(),orderId)||!Objects.equals(order.getUserId(),customerId)
            ||order.getStatus()!=OrderStatus.ORDERED||!Objects.equals(order.getActivePaymentId(),paymentId)
            ||payment.getProvider()!=PaymentProvider.PAYJP||payment.getPaymentMethod()!=PaymentMethod.CARD
            ||payment.getAmount()!=order.getTotalAmount()||payment.getProviderPaymentId()==null
            ||payment.getProviderPaymentId().isBlank())return Optional.empty();
        if(payments.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream().anyMatch(p->!Objects.equals(p.getId(),paymentId)
            &&p.getStatus()!=PaymentStatus.FAILED&&p.getStatus()!=PaymentStatus.CANCELLED))return Optional.empty();
        var history=transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId);
        var auth=history.stream().filter(t->t.getTransactionType()==PaymentTransactionType.AUTHORIZE).toList();
        if(auth.size()!=1)return Optional.empty();
        var t=auth.getFirst();
        if(t.getId()==null||!Objects.equals(t.getPayment().getId(),paymentId)||t.getPurpose()!=PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION
            ||t.getOrderChangeOperationId()!=null||t.getAmount()!=payment.getAmount()
            ||t.getOrderContentRevision()!=order.getContentRevision()||t.getIdempotencyKey()==null||t.getIdempotencyKey().isBlank()
            ||history.stream().anyMatch(x->!Objects.equals(x.getId(),t.getId())&&x.getStatus()==PaymentTransactionStatus.PENDING))return Optional.empty();
        boolean pending=t.getStatus()==PaymentTransactionStatus.PENDING&&t.getCompletedAt()==null
            &&(payment.getStatus()==PaymentStatus.PENDING||payment.getStatus()==PaymentStatus.REQUIRES_ACTION);
        boolean done=t.getStatus()==PaymentTransactionStatus.SUCCEEDED&&t.getCompletedAt()!=null
            &&payment.getStatus()==PaymentStatus.AUTHORIZED&&Objects.equals(t.getProviderTransactionId(),payment.getProviderPaymentId());
        return pending||done?Optional.of(new CheckoutTarget(orderId,paymentId,t.getId())):Optional.empty();
    }

}
