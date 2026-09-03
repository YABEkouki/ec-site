package com.example.ecsite.entity;

public enum OrderStatusHistoryActorType {

    USER("ユーザー"),
    ADMIN("管理者"),
    SYSTEM("システム");

    private final String displayName;

    OrderStatusHistoryActorType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}