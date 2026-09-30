package com.example.ecsite.service.pricing;

import java.util.List;

public record OrderAmount(
        int itemSubtotal,
        List<OrderChargeAmount> charges,
        int chargeTotal,
        int taxAmount,
        int totalAmount) {

    public OrderAmount {
        charges = List.copyOf(charges);
    }
}
