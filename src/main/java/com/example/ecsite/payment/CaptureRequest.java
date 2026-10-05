package com.example.ecsite.payment;

public record CaptureRequest(
        String providerPaymentId,
        int amount,
        String idempotencyKey) {
}
