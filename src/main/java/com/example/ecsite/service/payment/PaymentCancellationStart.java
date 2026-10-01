package com.example.ecsite.service.payment;

public record PaymentCancellationStart(
        Long paymentId,
        Long transactionId,
        String providerPaymentId,
        String idempotencyKey) {
}
