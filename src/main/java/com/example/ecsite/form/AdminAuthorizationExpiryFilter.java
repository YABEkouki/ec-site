package com.example.ecsite.form;

/** Feature135-A2: order list filter on the card authorization expiry (ORDERED orders with an AUTHORIZED payment). */
public enum AdminAuthorizationExpiryFilter {

    ATTENTION("期限切れ・24時間未満"),
    EXPIRED("期限切れ");

    private final String displayName;

    AdminAuthorizationExpiryFilter(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
