package com.example.ecsite.payment;

public record AuthorizationRequest(
        int amount,
        String idempotencyKey) {
}
