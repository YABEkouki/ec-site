package com.example.ecsite.service.payment;

import com.example.ecsite.entity.PaymentTransactionPurpose;

/** Pure policy. A valid future combination is deliberately not usable in stage 1A. */
public final class PaymentPurposePolicy {
    public boolean isValidCombination(String type, PaymentTransactionPurpose purpose) {
        if (type == null || purpose == null) return false;
        return switch (purpose) {
            case INITIAL_ORDER_AUTHORIZATION, ORDER_CHANGE_AUTHORIZATION -> type.equals("AUTHORIZE");
            case ORDER_CANCELLATION, ORDER_CHANGE_OLD_AUTH_CANCEL, ORDER_CHANGE_NEW_AUTH_ABORT -> type.equals("CANCEL");
            case SHIPMENT_CAPTURE -> type.equals("CAPTURE");
            case LEGACY_UNKNOWN -> false;
        };
    }

    public boolean isUsableInStage1A(String type, PaymentTransactionPurpose purpose) {
        if (!isValidCombination(type, purpose)) return false;
        return switch (purpose) {
            case INITIAL_ORDER_AUTHORIZATION, ORDER_CANCELLATION, SHIPMENT_CAPTURE -> true;
            default -> false;
        };
    }
}
