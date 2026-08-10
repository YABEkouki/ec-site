package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.cart.CartItem;
import com.example.ecsite.entity.Product;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private ProductService productService;

    @Test
    void addItemRejectsWhenCombinedQuantityExceedsStock() {

        Long productId = 1L;

        Cart cart = new Cart();

        cart.addItem(
                new CartItem(
                        productId,
                        "テスト商品",
                        1000,
                        2));

        Product product = mock(Product.class);

        when(productService.findById(productId))
                .thenReturn(product);

        when(product.getName())
                .thenReturn("テスト商品");

        when(product.getStock())
                .thenReturn(3);

        CartService cartService =
                new CartService(productService);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> cartService.addItem(
                                cart,
                                productId,
                                2));

        assertEquals(
                "テスト商品の在庫は3個です。",
                exception.getMessage());

        assertEquals(
                2,
                cart.getQuantity(productId));
    }

    @Test
    void updateQuantityRejectsWhenQuantityExceedsStock() {

        Long productId = 1L;

        Cart cart = new Cart();

        cart.addItem(
                new CartItem(
                        productId,
                        "テスト商品",
                        1000,
                        2));

        Product product = mock(Product.class);

        when(productService.findById(productId))
                .thenReturn(product);

        when(product.getName())
                .thenReturn("テスト商品");

        when(product.getStock())
                .thenReturn(3);

        CartService cartService =
                new CartService(productService);

        assertThrows(
                IllegalArgumentException.class,
                () -> cartService.updateQuantity(
                        cart,
                        productId,
                        4));

        assertEquals(
                2,
                cart.getQuantity(productId));
    }
}