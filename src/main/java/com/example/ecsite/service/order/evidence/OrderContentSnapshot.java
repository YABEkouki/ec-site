package com.example.ecsite.service.order.evidence;

import java.util.List;

/** Detached persisted content only. No Entity traversal, clocks, workflow state or IO. */
public record OrderContentSnapshot(int schemaVersion, long orderId, long customerId, int contentRevision,
        List<Item> items, List<Charge> charges, int itemSubtotal, int chargeTotal, int taxAmount, int totalAmount) {
    public OrderContentSnapshot {
        items = List.copyOf(items);
        charges = List.copyOf(charges);
    }

    public record Item(long orderItemId, long productId, String productName, long categoryId, String categoryName,
            int unitPrice, int quantity, int subtotal, long taxCategoryId, String taxCategoryCode,
            String taxCategoryName, int taxRateBasisPoints) {}

    public record Charge(long orderChargeId, String type, String name, int amount, long taxCategoryId,
            String taxCategoryCode, String taxCategoryName, int taxRateBasisPoints, int displayOrder) {}

    @Override public String toString() { return "OrderContentSnapshot[redacted]"; }
}
