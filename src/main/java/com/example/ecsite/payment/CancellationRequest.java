package com.example.ecsite.payment;

public record CancellationRequest(
        String providerPaymentId,
        String idempotencyKey) {
}
