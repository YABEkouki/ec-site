package com.example.ecsite.service.payment;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.PaymentTransactionRepository;

@Service
public class PaymentOperationSnapshotService {
    private final PaymentTransactionRepository transactions;
    public PaymentOperationSnapshotService(PaymentTransactionRepository transactions) {
        this.transactions = transactions;
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
}
