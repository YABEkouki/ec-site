package com.example.ecsite.entity;

/** Business intent only; introducing this enum does not enable order-change operations. */
public enum PaymentTransactionPurpose {
    INITIAL_ORDER_AUTHORIZATION,
    ORDER_CANCELLATION,
    SHIPMENT_CAPTURE,
    ORDER_CHANGE_AUTHORIZATION,
    ORDER_CHANGE_OLD_AUTH_CANCEL,
    ORDER_CHANGE_NEW_AUTH_ABORT,
    LEGACY_UNKNOWN
}
