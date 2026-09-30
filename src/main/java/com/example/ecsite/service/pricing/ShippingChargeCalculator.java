package com.example.ecsite.service.pricing;

import org.springframework.stereotype.Component;

import com.example.ecsite.entity.OrderChargeType;
import com.example.ecsite.entity.TaxCategory;

@Component
public class ShippingChargeCalculator {

    private static final int SHIPPING_FEE = 550;
    private static final int FREE_SHIPPING_THRESHOLD = 5000;
    private static final String SHIPPING_CHARGE_NAME = "送料・梱包料";
    private static final int SHIPPING_DISPLAY_ORDER = 10;

    public OrderChargeAmount calculate(
            OrderPricingContext context,
            TaxCategory taxCategory) {

        return calculate(
                context,
                ChargeTaxSnapshot.from(taxCategory));
    }

    public OrderChargeAmount calculate(
            OrderPricingContext context,
            ChargeTaxSnapshot taxSnapshot) {

        int itemSubtotal = context.getItems()
                .stream()
                .mapToInt(OrderPricingContext.Item::getSubtotal)
                .sum();

        int shippingFee = itemSubtotal >= FREE_SHIPPING_THRESHOLD
                ? 0
                : SHIPPING_FEE;

        return new OrderChargeAmount(
                OrderChargeType.SHIPPING,
                SHIPPING_CHARGE_NAME,
                shippingFee,
                taxSnapshot.taxCategoryId(),
                taxSnapshot.taxCategoryCode(),
                taxSnapshot.taxCategoryName(),
                taxSnapshot.taxRate(),
                SHIPPING_DISPLAY_ORDER);
    }
}
