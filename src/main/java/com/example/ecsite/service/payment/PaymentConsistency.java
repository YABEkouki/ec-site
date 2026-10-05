package com.example.ecsite.service.payment;

public record PaymentConsistency(
        PaymentConsistencyStatus status,
        int orderAmount,
        int paymentAmount,
        int authorizationAmount,
        int orderContentRevision,
        int authorizationContentRevision) {

    public boolean canCapture() {
        return status == PaymentConsistencyStatus.CONSISTENT;
    }
}
