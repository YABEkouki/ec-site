package com.example.ecsite.form;

/**
 * Feature135-A2: order list filter on the card authorization expiry (ORDERED orders with an AUTHORIZED payment).
 * Feature135-C1 adds the shipped orders left unpaid by a canceled Flow.
 */
public enum AdminAuthorizationExpiryFilter {

    ATTENTION("期限切れ・24時間未満"),
    EXPIRED("期限切れ"),
    /** Feature135-C1: shipped orders whose capture failed because PAY.JP had canceled the Flow (V95), still unpaid. */
    CAPTURE_CANCELED("発送済み・売上未確定（与信取消）");

    private final String displayName;

    AdminAuthorizationExpiryFilter(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
