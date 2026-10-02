package com.example.ecsite.payment;

public record CaptureRequest(
        String providerPaymentId,
        String idempotencyKey) {
}
