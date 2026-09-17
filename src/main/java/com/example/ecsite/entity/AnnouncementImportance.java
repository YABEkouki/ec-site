package com.example.ecsite.entity;

public enum AnnouncementImportance {

    NORMAL("通常"),
    IMPORTANT("重要"),
    URGENT("緊急");

    private final String displayName;

    AnnouncementImportance(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
