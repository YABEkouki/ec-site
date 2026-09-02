package com.example.ecsite.dto;

public record CustomerSalesRanking(
        Long userId,
        String username,
        long orderCount,
        long quantity,
        long salesAmount) {
}
