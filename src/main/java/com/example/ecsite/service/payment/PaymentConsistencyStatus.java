package com.example.ecsite.service.payment;

public enum PaymentConsistencyStatus {

    CONSISTENT,
    AMOUNT_DECREASED,
    AMOUNT_INCREASED,
    REVISION_MISMATCH,
    AUTHORIZATION_MISMATCH
}
