package com.example.ecsite.payment;

public record PaymentFlowState(
        String providerPaymentId,
        PaymentFlowStatus status,
        String failureCode,
        String failureMessage) {
}
