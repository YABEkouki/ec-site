package com.example.ecsite.service.payment;

import com.example.ecsite.entity.*;

/** Immutable identity and request values, materialized before any provider call. */
public record PaymentOperationSnapshot(
        Long orderId, Long paymentId, Long transactionId, PaymentTransactionType type,
        String idempotencyKey, int amount, int orderContentRevision, String providerPaymentId,
        PaymentProvider provider, PaymentTransactionStatus status,
        PaymentTransactionInitiatorType initiatorType, Long initiatorId,
        String initiatorUsername, String internalNote, PaymentTransactionPurpose purpose) {

    public PaymentOperationSnapshot(Long orderId, Long paymentId, Long transactionId, PaymentTransactionType type,
            String key, int amount, int revision, String providerId, PaymentProvider provider, PaymentTransactionStatus status,
            PaymentTransactionInitiatorType initiatorType, Long initiatorId, String username, String note) {
        this(orderId, paymentId, transactionId, type, key, amount, revision, providerId, provider, status,
                initiatorType, initiatorId, username, note, null);
    }

    static PaymentOperationSnapshot from(PaymentTransaction transaction) {
        return from(transaction.getPayment(), transaction);
    }

    static PaymentOperationSnapshot from(Payment payment, PaymentTransaction transaction) {
        return new PaymentOperationSnapshot(payment.getOrder().getId(), payment.getId(), transaction.getId(),
                transaction.getTransactionType(), transaction.getIdempotencyKey(), transaction.getAmount(),
                transaction.getOrderContentRevision(), payment.getProviderPaymentId(), payment.getProvider(),
                transaction.getStatus(), transaction.getInitiatorType(), transaction.getInitiatorId(),
                transaction.getInitiatorUsername(), transaction.getInternalNote(), transaction.getPurpose());
    }
}
