package com.example.ecsite.service.pricing;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ecsite.entity.TaxCategory;

@Component
public class OrderAmountCalculator {

    private final ShippingChargeCalculator shippingChargeCalculator;
    private final TaxCalculator taxCalculator;

    public OrderAmountCalculator(
            ShippingChargeCalculator shippingChargeCalculator,
            TaxCalculator taxCalculator) {

        this.shippingChargeCalculator = shippingChargeCalculator;
        this.taxCalculator = taxCalculator;
    }

    public OrderAmount calculate(
            OrderPricingContext context,
            TaxCategory shippingTaxCategory) {

        int itemSubtotal = context.getItems()
                .stream()
                .mapToInt(OrderPricingContext.Item::getSubtotal)
                .sum();

        OrderChargeAmount shippingCharge =
                shippingChargeCalculator.calculate(
                        context,
                        shippingTaxCategory);

        List<OrderChargeAmount> charges =
                List.of(shippingCharge);

        int chargeTotal = charges.stream()
                .mapToInt(OrderChargeAmount::amount)
                .sum();

        int taxAmount = taxCalculator.calculate(
                context,
                charges);

        int totalAmount = itemSubtotal + chargeTotal;

        return new OrderAmount(
                itemSubtotal,
                charges,
                chargeTotal,
                taxAmount,
                totalAmount);
    }
}
