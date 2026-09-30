package com.example.ecsite.entity;

public enum OrderContentChangeHistoryActorType {

    USER("ユーザー"),
    ADMIN("管理者"),
    SYSTEM("システム");

    private final String displayName;

    OrderContentChangeHistoryActorType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
