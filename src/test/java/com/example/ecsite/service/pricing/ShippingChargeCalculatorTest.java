package com.example.ecsite.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.OrderChargeType;
import com.example.ecsite.entity.TaxCategory;

class ShippingChargeCalculatorTest {

    private final ShippingChargeCalculator calculator =
            new ShippingChargeCalculator();

    @Test
    void chargesShippingWhenItemSubtotalIsBelowFreeShippingThreshold() {

        OrderPricingContext context = createContext(4999);

        OrderChargeAmount charge = calculator.calculate(
                context,
                createStandardTaxCategory());

        assertThat(charge.chargeType())
                .isEqualTo(OrderChargeType.SHIPPING);
        assertThat(charge.name())
                .isEqualTo("送料・梱包料");
        assertThat(charge.amount())
                .isEqualTo(550);
        assertThat(charge.taxCategoryCode())
                .isEqualTo("STANDARD");
        assertThat(charge.taxRate())
                .isEqualByComparingTo("10.00");
        assertThat(charge.displayOrder())
                .isEqualTo(10);
    }

    @Test
    void shippingIsFreeWhenItemSubtotalEqualsThreshold() {

        OrderPricingContext context = createContext(5000);

        OrderChargeAmount charge = calculator.calculate(
                context,
                createStandardTaxCategory());

        assertThat(charge.amount()).isZero();
    }

    @Test
    void shippingIsFreeWhenItemSubtotalExceedsThreshold() {

        OrderPricingContext context = createContext(5001);

        OrderChargeAmount charge = calculator.calculate(
                context,
                createStandardTaxCategory());

        assertThat(charge.amount()).isZero();
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
