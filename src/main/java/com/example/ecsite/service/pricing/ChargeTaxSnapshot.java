package com.example.ecsite.service.pricing;

import java.math.BigDecimal;

import com.example.ecsite.entity.OrderCharge;
import com.example.ecsite.entity.TaxCategory;

public record ChargeTaxSnapshot(
        Long taxCategoryId,
        String taxCategoryCode,
        String taxCategoryName,
        BigDecimal taxRate) {

    public static ChargeTaxSnapshot from(TaxCategory taxCategory) {
        return new ChargeTaxSnapshot(
                taxCategory.getId(),
                taxCategory.getCode(),
                taxCategory.getName(),
                taxCategory.getTaxRate());
    }

    public static ChargeTaxSnapshot from(OrderCharge charge) {
        return new ChargeTaxSnapshot(
                charge.getTaxCategoryId(),
                charge.getTaxCategoryCode(),
                charge.getTaxCategoryName(),
                charge.getTaxRate());
    }
}
