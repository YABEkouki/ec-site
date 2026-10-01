package com.example.ecsite.payment;

public record CancellationResult(
        CancellationResultStatus status,
        String providerTransactionId) {
}
