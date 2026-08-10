package com.example.ecsite.cart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Cart {

    private final List<CartItem> items = new ArrayList<>();

    private static final int MAX_QUANTITY = 99;

    public List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void addItem(CartItem newItem) {

        validateQuantity(newItem.getQuantity());

        for (CartItem item : items) {

            if (item.getProductId()
                    .equals(newItem.getProductId())) {

                int newQuantity = item.getQuantity()
                        + newItem.getQuantity();

                validateQuantity(newQuantity);

                item.setQuantity(newQuantity);
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

    public void updateQuantity(
            Long productId,
            int quantity) {

        validateQuantity(quantity);

        for (CartItem item : items) {

            if (item.getProductId()
                    .equals(productId)) {

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

    private void validateQuantity(int quantity) {

        if (quantity < 1
                || quantity > MAX_QUANTITY) {

            throw new IllegalArgumentException(
                    "数量は1以上99以下で指定してください。");
        }
    }
}