package com.example.ecsite.payment;

public enum AuthorizationResultStatus {
    PENDING,
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_CONFIRMATION,
    REQUIRES_ACTION,
    AUTHORIZED,
    FAILED
}
