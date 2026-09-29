package com.example.ecsite.service.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OrderPricingContext {

    private final List<Item> items = new ArrayList<>();

    public void addItem(
            int price,
            int quantity,
            Long taxCategoryId,
            String taxCategoryCode,
            String taxCategoryName,
            BigDecimal taxRate) {

        items.add(new Item(
                price,
                quantity,
                taxCategoryId,
                taxCategoryCode,
                taxCategoryName,
                taxRate));
    }

    public List<Item> getItems() {
        return Collections.unmodifiableList(items);
    }

    public record Item(
            int price,
            int quantity,
            Long taxCategoryId,
            String taxCategoryCode,
            String taxCategoryName,
            BigDecimal taxRate) {

        public int getSubtotal() {
            return price * quantity;
        }
    }
}
