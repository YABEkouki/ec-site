package com.example.ecsite.entity;

public enum AnnouncementType {

    GENERAL("一般"),
    PRODUCT("商品"),
    SHIPPING("配送"),
    MAINTENANCE("メンテナンス");

    private final String displayName;

    AnnouncementType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
