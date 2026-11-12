package com.example.ecsite.service.order.history;

import java.util.Objects;

public record CustomerOrderChangeItemDelta(Kind kind, Side before, Side after) {
    public enum Kind { ADDED, REMOVED, UPDATED }
    public record Side(String productName, int quantity, int unitPrice, int subtotal) {
        public Side { Objects.requireNonNull(productName); }
    }
    public CustomerOrderChangeItemDelta {
        Objects.requireNonNull(kind);
        if (kind == Kind.ADDED && (before != null || after == null)
                || kind == Kind.REMOVED && (before == null || after != null)
                || kind == Kind.UPDATED && (before == null || after == null))
            throw new IllegalArgumentException("Invalid display sides");
    }
}
