package com.example.ecsite.dto;

import java.time.LocalDateTime;

public record AdminCustomerPurchaseSummary(
        long orderCount,
        long purchaseAmount,
        LocalDateTime lastOrderedAt) {
}
