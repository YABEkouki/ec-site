package com.example.ecsite.dto;

import java.math.BigDecimal;

public record SalesMetricComparison(
        long currentValue,
        long previousValue,
        long difference,
        BigDecimal changeRate) {
}
