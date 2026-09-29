package com.example.ecsite.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.OrderChargeType;

class TaxCalculatorTest {

    private final TaxCalculator taxCalculator = new TaxCalculator();

    @Test
    void calculatesIncludedTaxForSingleTaxRate() {

        OrderPricingContext context = new OrderPricingContext();

        context.addItem(
                1100,
                2,
                1L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"));

        int taxAmount = taxCalculator.calculate(
                context,
                List.of());

        assertThat(taxAmount).isEqualTo(200);
    }

    @Test
    void calculatesTaxOnceForEachTaxRate() {

        OrderPricingContext context = new OrderPricingContext();

        context.addItem(
                1080,
                1,
                2L,
                "REDUCED",
                "軽減税率",
                new BigDecimal("8.00"));

        context.addItem(
                1100,
                1,
                1L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"));

        int taxAmount = taxCalculator.calculate(
                context,
                List.of());

        assertThat(taxAmount).isEqualTo(180);
    }

    @Test
    void includesChargesInTaxableAmountForSameTaxRate() {

        OrderPricingContext context = new OrderPricingContext();

        context.addItem(
                1100,
                1,
                1L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"));

        OrderChargeAmount shipping = new OrderChargeAmount(
                OrderChargeType.SHIPPING,
                "送料・梱包料",
                550,
                1L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"),
                10);

        int taxAmount = taxCalculator.calculate(
                context,
                List.of(shipping));

        assertThat(taxAmount).isEqualTo(150);
    }

    @Test
    void roundsDownOnceAfterAggregatingSameTaxRate() {

        OrderPricingContext context = new OrderPricingContext();

        context.addItem(
                109,
                1,
                1L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"));

        context.addItem(
                109,
                1,
                1L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"));

        int taxAmount = taxCalculator.calculate(
                context,
                List.of());

        assertThat(taxAmount).isEqualTo(19);
    }

    @Test
    void returnsZeroWhenThereAreNoItemsOrCharges() {

        OrderPricingContext context = new OrderPricingContext();

        int taxAmount = taxCalculator.calculate(
                context,
                List.of());

        assertThat(taxAmount).isZero();
    }

    @Test
    void calculatesTaxSeparatelyForItemsAndChargesWithDifferentRates() {

        OrderPricingContext context = new OrderPricingContext();

        context.addItem(
                1080,
                1,
                2L,
                "REDUCED",
                "軽減税率",
                new BigDecimal("8.00"));

        OrderChargeAmount shipping = new OrderChargeAmount(
                OrderChargeType.SHIPPING,
                "送料・梱包料",
                550,
                1L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"),
                10);

        int taxAmount = taxCalculator.calculate(
                context,
                List.of(shipping));

        assertThat(taxAmount).isEqualTo(130);
    }

    @Test
    void treatsTaxRatesWithDifferentScalesAsSameTaxRate() {

        OrderPricingContext context = new OrderPricingContext();

        context.addItem(
                109,
                1,
                1L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.0"));

        context.addItem(
                109,
                1,
                1L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"));

        int taxAmount = taxCalculator.calculate(
                context,
                List.of());

        assertThat(taxAmount).isEqualTo(19);
    }

}
