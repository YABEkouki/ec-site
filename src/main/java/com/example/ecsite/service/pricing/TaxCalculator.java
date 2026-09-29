package com.example.ecsite.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class TaxCalculator {

    private static final BigDecimal ONE_HUNDRED =
            new BigDecimal("100");

    public int calculate(
            OrderPricingContext context,
            List<OrderChargeAmount> charges) {

        Map<BigDecimal, Integer> grossAmountByTaxRate =
                new HashMap<>();

        for (OrderPricingContext.Item item : context.getItems()) {

            grossAmountByTaxRate.merge(
                    item.taxRate(),
                    item.getSubtotal(),
                    Integer::sum);
        }

        for (OrderChargeAmount charge : charges) {

            grossAmountByTaxRate.merge(
                    charge.taxRate(),
                    charge.amount(),
                    Integer::sum);
        }

        int totalTaxAmount = 0;

        for (Map.Entry<BigDecimal, Integer> entry
                : grossAmountByTaxRate.entrySet()) {

            BigDecimal taxRate = entry.getKey();
            int grossAmount = entry.getValue();

            BigDecimal taxAmount = BigDecimal
                    .valueOf(grossAmount)
                    .multiply(taxRate)
                    .divide(
                            ONE_HUNDRED.add(taxRate),
                            0,
                            RoundingMode.DOWN);

            totalTaxAmount += taxAmount.intValueExact();
        }

        return totalTaxAmount;
    }
}
