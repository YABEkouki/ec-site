package com.example.ecsite.payment;

public record PaymentFlowState(
        String providerPaymentId,
        PaymentFlowStatus status,
        String failureCode,
        String failureMessage,
        /** Feature135-B2: PAY.JP cancellation_reason of a canceled Flow; null when absent. */
        String cancellationReason) {

    public PaymentFlowState(String providerPaymentId, PaymentFlowStatus status, String failureCode, String failureMessage) {
        this(providerPaymentId, status, failureCode, failureMessage, null);
    }
}
