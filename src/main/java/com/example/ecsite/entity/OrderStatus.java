package com.example.ecsite.entity;

public enum OrderStatus {

    ORDERED("注文受付"),
    PAID("支払済み"),
    SHIPPED("発送済み"),
    CANCELLED("キャンセル");

    private final String displayName;

    OrderStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}