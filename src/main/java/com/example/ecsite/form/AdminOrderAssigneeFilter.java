package com.example.ecsite.form;

public enum AdminOrderAssigneeFilter {

    ALL("すべて"),
    UNASSIGNED("未担当"),
    ME("自分が担当"),
    SPECIFIC("特定管理者");

    private final String displayName;

    AdminOrderAssigneeFilter(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}