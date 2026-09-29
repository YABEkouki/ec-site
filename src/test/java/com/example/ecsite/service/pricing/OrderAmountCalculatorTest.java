package com.example.ecsite.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.OrderChargeType;
import com.example.ecsite.entity.TaxCategory;

class OrderAmountCalculatorTest {

    private final OrderAmountCalculator calculator =
            new OrderAmountCalculator(
                    new ShippingChargeCalculator(),
                    new TaxCalculator());

    @Test
    void calculatesOrderAmountWithShippingCharge() {

        OrderPricingContext context = createContext(4400);

        OrderAmount amount = calculator.calculate(
                context,
                createStandardTaxCategory());

        assertThat(amount.itemSubtotal()).isEqualTo(4400);
        assertThat(amount.chargeTotal()).isEqualTo(550);
        assertThat(amount.totalAmount()).isEqualTo(4950);
        assertThat(amount.taxAmount()).isEqualTo(450);

        assertThat(amount.charges()).hasSize(1);

        OrderChargeAmount charge = amount.charges().get(0);

        assertThat(charge.chargeType())
                .isEqualTo(OrderChargeType.SHIPPING);
        assertThat(charge.amount()).isEqualTo(550);
    }

    @Test
    void calculatesOrderAmountWithFreeShipping() {

        OrderPricingContext context = createContext(5500);

        OrderAmount amount = calculator.calculate(
                context,
                createStandardTaxCategory());

        assertThat(amount.itemSubtotal()).isEqualTo(5500);
        assertThat(amount.chargeTotal()).isZero();
        assertThat(amount.totalAmount()).isEqualTo(5500);
        assertThat(amount.taxAmount()).isEqualTo(500);

        assertThat(amount.charges()).hasSize(1);
        assertThat(amount.charges().get(0).amount()).isZero();
    }

    private OrderPricingContext createContext(int price) {

        OrderPricingContext context = new OrderPricingContext();

        context.addItem(
                price,
                1,
                1L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"));

        return context;
    }

    private TaxCategory createStandardTaxCategory() {

        TaxCategory taxCategory = new TaxCategory();

        taxCategory.setCode("STANDARD");
        taxCategory.setName("標準税率");
        taxCategory.setTaxRate(new BigDecimal("10.00"));
        taxCategory.setActive(true);
        taxCategory.setDisplayOrder(10);

        return taxCategory;
    }
}
