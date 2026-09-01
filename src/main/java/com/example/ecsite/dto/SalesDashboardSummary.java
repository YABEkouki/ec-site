package com.example.ecsite.dto;

import java.time.LocalDate;

public record SalesDashboardSummary(
        SalesSummary current,
        LocalDate comparisonFrom,
        LocalDate comparisonTo,
        SalesMetricComparison totalOrderCount,
        SalesMetricComparison salesOrderCount,
        SalesMetricComparison salesAmount) {
}
