package com.example.ecsite.payment;

public record AuthorizationResult(
        AuthorizationResultStatus status,
        String providerTransactionId,
        String failureCode,
        String failureMessage) {
}
