package com.example.ecsite.service.emergency;

/** Feature136: why an order could be shipped in an emergency (stored in emergency_shipping_preparations.mode). */
public enum EmergencyShippingMode {
    /** The change deadline has passed (136-A); the order was not picked up by the warehouse export. */
    AFTER_DEADLINE("変更期限後"),
    /** Accepted at or after 14:00 and shipped on its acceptance day, before the next day's deadline (136-C1). */
    SAME_DAY("14時以降受付の当日出荷");

    private final String label;

    EmergencyShippingMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
