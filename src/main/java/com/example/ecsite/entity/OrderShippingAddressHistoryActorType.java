package com.example.ecsite.entity;

public enum OrderShippingAddressHistoryActorType {

    USER("ユーザー"),
    ADMIN("管理者"),
    SYSTEM("システム");

    private final String displayName;

    OrderShippingAddressHistoryActorType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
