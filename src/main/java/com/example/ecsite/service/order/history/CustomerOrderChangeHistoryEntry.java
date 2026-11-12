package com.example.ecsite.service.order.history;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Customer data only. changedAt is displayed in Asia/Tokyo for both saved time forms. */
public record CustomerOrderChangeHistoryEntry(LocalDateTime changedAt, Kind kind,
        List<CustomerOrderChangeItemDelta> items, CustomerOrderChangeAmounts before,
        CustomerOrderChangeAmounts after, CustomerOrderChangeTaxCapability taxCapability) {
    public enum Kind { CONTENT_CHANGE, QUANTITY_DECREASE, QUANTITY_INCREASE }
    public CustomerOrderChangeHistoryEntry {
        Objects.requireNonNull(changedAt); Objects.requireNonNull(kind);
        items = List.copyOf(items); Objects.requireNonNull(before); Objects.requireNonNull(after);
        Objects.requireNonNull(taxCapability);
    }
}
