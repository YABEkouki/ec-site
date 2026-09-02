package com.example.ecsite.dto;

public record CategorySalesRanking(
        Long categoryId,
        String categoryName,
        long quantity,
        long orderCount,
        long salesAmount) {
}
