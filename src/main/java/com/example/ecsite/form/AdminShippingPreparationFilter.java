package com.example.ecsite.form;

/**
 * Feature137: order list filter on shipping preparation (decision 8: 出荷準備中 is shown, not stored as a status).
 * PREPARING = ORDERED or PAID with shipping preparation started; NOT_PREPARING = shipping preparation not started.
 */
public enum AdminShippingPreparationFilter {

    PREPARING("出荷準備中"),
    NOT_PREPARING("出荷準備前");

    private final String displayName;

    AdminShippingPreparationFilter(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
