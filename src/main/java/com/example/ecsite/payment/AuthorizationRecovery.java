package com.example.ecsite.payment;

public record AuthorizationRecovery(
        AuthorizationResult result,
        String clientSecret) {
}
