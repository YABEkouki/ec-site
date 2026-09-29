package com.example.ecsite.service.pricing;

import java.math.BigDecimal;

import com.example.ecsite.entity.OrderChargeType;

public record OrderChargeAmount(
        OrderChargeType chargeType,
        String name,
        int amount,
        Long taxCategoryId,
        String taxCategoryCode,
        String taxCategoryName,
        BigDecimal taxRate,
        int displayOrder) {
}
