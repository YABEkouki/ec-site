package com.example.ecsite.service.payment;

public record PaymentCaptureStart(
        Long paymentId,
        Long transactionId,
        String providerPaymentId,
        String idempotencyKey) {
}
