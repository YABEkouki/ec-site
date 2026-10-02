package com.example.ecsite.payment;

public record CaptureResult(
        CaptureResultStatus status,
        String providerTransactionId,
        String failureCode,
        String failureMessage) {
}
