package com.example.ecsite.dto;

public record ProductSalesRanking(
        Long productId,
        String productName,
        long quantity,
        long orderCount,
        long salesAmount) {
}
