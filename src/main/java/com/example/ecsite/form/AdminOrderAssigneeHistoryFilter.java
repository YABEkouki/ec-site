package com.example.ecsite.form;

public enum AdminOrderAssigneeHistoryFilter {

    ALL("指定なし"),
    UNASSIGNED("未担当"),
    SPECIFIC("管理者を選択");

    private final String displayName;

    AdminOrderAssigneeHistoryFilter(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
