package com.example.ecsite.dto;

public record SalesSummary(
                long totalOrderCount,
                long salesOrderCount,
                long salesAmount,
                long orderedCount,
                long paidCount,
                long shippedCount,
                long cancelledCount) {
}