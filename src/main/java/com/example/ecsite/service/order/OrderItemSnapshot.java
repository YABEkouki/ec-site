package com.example.ecsite.service.order;

import java.math.BigDecimal;

import com.example.ecsite.entity.OrderItem;

public record OrderItemSnapshot(
        Long orderItemId,
        Long productId,
        String productName,
        int price,
        int quantity,
        int subtotal,
        Long taxCategoryId,
        String taxCategoryCode,
        String taxCategoryName,
        BigDecimal taxRate) {

    public static OrderItemSnapshot from(OrderItem item) {
        return new OrderItemSnapshot(
                item.getId(),
                item.getProductId(),
                item.getProductName(),
                item.getPrice(),
                item.getQuantity(),
                item.getSubtotal(),
                item.getTaxCategoryId(),
                item.getTaxCategoryCode(),
                item.getTaxCategoryName(),
                item.getTaxRate());
    }
}
