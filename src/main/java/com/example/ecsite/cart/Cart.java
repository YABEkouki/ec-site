package com.example.ecsite.cart;

import java.util.ArrayList;
import java.util.List;

public class Cart {

    private final List<CartItem> items = new ArrayList<>();

    public List<CartItem> getItems() {
        return items;
    }

    public void addItem(CartItem newItem) {

        for (CartItem item : items) {
            if (item.getProductId().equals(newItem.getProductId())) {
                item.setQuantity(
                        item.getQuantity() + newItem.getQuantity());
                return;
            }
        }

        items.add(newItem);
    }

    public int getTotalAmount() {
        return items.stream()
                .mapToInt(CartItem::getSubtotal)
                .sum();
    }

    public int getTotalQuantity() {
        return items.stream()
                .mapToInt(CartItem::getQuantity)
                .sum();
    }
}