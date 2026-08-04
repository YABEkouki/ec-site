package com.example.ecsite.cart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Cart {

    private final List<CartItem> items = new ArrayList<>();

    public List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void addItem(CartItem newItem) {

        if (newItem.getQuantity() < 1) {
            throw new IllegalArgumentException(
                    "数量は1以上で指定してください。");
        }

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

    public void updateQuantity(Long productId, int quantity) {

        if (quantity < 1) {
            throw new IllegalArgumentException(
                    "数量は1以上で指定してください。");
        }

        for (CartItem item : items) {
            if (item.getProductId().equals(productId)) {
                item.setQuantity(quantity);
                return;
            }
        }
    }

    public void removeItem(Long productId) {
        items.removeIf(
                item -> item.getProductId().equals(productId));
    }

    public void clear() {
        items.clear();
    }

    public void refreshPrice(
            Long productId,
            int newPrice) {

        if (newPrice < 0) {
            throw new IllegalArgumentException(
                    "価格は0以上で指定してください。");
        }

        for (CartItem item : items) {
            if (item.getProductId().equals(productId)) {
                item.setPrice(newPrice);
                return;
            }
        }
    }
}