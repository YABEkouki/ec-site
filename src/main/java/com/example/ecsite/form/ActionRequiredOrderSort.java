package com.example.ecsite.form;

public enum ActionRequiredOrderSort {

    OLDEST("滞留が長い順"),
    NEWEST("更新が新しい順");

    private final String displayName;

    ActionRequiredOrderSort(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
