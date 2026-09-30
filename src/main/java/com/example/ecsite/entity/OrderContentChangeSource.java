package com.example.ecsite.entity;

public enum OrderContentChangeSource {

    CUSTOMER("お客様"),
    ADMIN("店舗"),
    SYSTEM("システム");

    private final String displayName;

    OrderContentChangeSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
