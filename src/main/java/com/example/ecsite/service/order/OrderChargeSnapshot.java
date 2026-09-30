package com.example.ecsite.service.order;

import java.math.BigDecimal;

import com.example.ecsite.entity.OrderCharge;
import com.example.ecsite.entity.OrderChargeType;

public record OrderChargeSnapshot(
        Long orderChargeId,
        OrderChargeType chargeType,
        String name,
        int amount,
        Long taxCategoryId,
        String taxCategoryCode,
        String taxCategoryName,
        BigDecimal taxRate,
        int displayOrder) {

    public static OrderChargeSnapshot from(OrderCharge charge) {
        return new OrderChargeSnapshot(
                charge.getId(),
                charge.getChargeType(),
                charge.getName(),
                charge.getAmount(),
                charge.getTaxCategoryId(),
                charge.getTaxCategoryCode(),
                charge.getTaxCategoryName(),
                charge.getTaxRate(),
                charge.getDisplayOrder());
    }
}
