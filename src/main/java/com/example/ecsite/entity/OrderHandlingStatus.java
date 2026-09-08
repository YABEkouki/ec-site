package com.example.ecsite.entity;

public enum OrderHandlingStatus {

    NONE("通常"),
    NEEDS_ACTION("要対応"),
    IN_PROGRESS("対応中"),
    RESOLVED("対応済み");

    private final String displayName;

    OrderHandlingStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
