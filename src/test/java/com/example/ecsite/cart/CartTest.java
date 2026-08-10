package com.example.ecsite.cart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CartTest {

    @Test
    void addItemRejectsQuantityGreaterThan99() {

        Cart cart = new Cart();

        CartItem item =
                new CartItem(
                        1L,
                        "テスト商品",
                        1000,
                        100);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> cart.addItem(item));

        assertEquals(
                "数量は1以上99以下で指定してください。",
                exception.getMessage());
    }

    @Test
    void addItemRejectsWhenCombinedQuantityExceeds99() {

        Cart cart = new Cart();

        cart.addItem(
                new CartItem(
                        1L,
                        "テスト商品",
                        1000,
                        98));

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> cart.addItem(
                                new CartItem(
                                        1L,
                                        "テスト商品",
                                        1000,
                                        2)));

        assertEquals(
                "数量は1以上99以下で指定してください。",
                exception.getMessage());

        assertEquals(
                98,
                cart.getItems().get(0).getQuantity());
    }

    @Test
    void addItemAllowsCombinedQuantityOf99() {

        Cart cart = new Cart();

        cart.addItem(
                new CartItem(
                        1L,
                        "テスト商品",
                        1000,
                        98));

        cart.addItem(
                new CartItem(
                        1L,
                        "テスト商品",
                        1000,
                        1));

        assertEquals(
                99,
                cart.getItems().get(0).getQuantity());
    }

    @Test
    void updateQuantityRejectsQuantityGreaterThan99() {

        Cart cart = new Cart();

        cart.addItem(
                new CartItem(
                        1L,
                        "テスト商品",
                        1000,
                        1));

        assertThrows(
                IllegalArgumentException.class,
                () -> cart.updateQuantity(
                        1L,
                        100));

        assertEquals(
                1,
                cart.getItems().get(0).getQuantity());
    }
}