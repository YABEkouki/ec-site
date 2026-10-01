package com.example.ecsite.service.payment;

public record PaymentAuthorizationStart(
        Long paymentId,
        Long transactionId,
        int amount,
        String idempotencyKey) {
}
