package com.example.ecsite.service.payment;

public enum PaymentConsistencyStatus {

    CONSISTENT,
    EVIDENCE_VERIFIED,
    EVIDENCE_REQUIRED,
    AMOUNT_DECREASED,
    AMOUNT_INCREASED,
    REVISION_MISMATCH,
    AUTHORIZATION_MISMATCH
}
