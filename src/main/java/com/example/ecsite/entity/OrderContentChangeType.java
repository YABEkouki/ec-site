package com.example.ecsite.entity;

public enum OrderContentChangeType {

    ADDED("追加"),
    UPDATED("変更"),
    REMOVED("削除");

    private final String displayName;

    OrderContentChangeType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
