package com.example.ecsite.service;

import org.springframework.stereotype.Service;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.cart.CartItem;
import com.example.ecsite.entity.Product;

@Service
public class CartService {

    private final ProductService productService;

    public CartService(ProductService productService) {
        this.productService = productService;
    }

    public void addItem(
            Cart cart,
            Long productId,
            int quantity) {

        Product product = productService.findById(productId);

        long quantityAfterAddition = (long) cart.getQuantity(productId)
                + quantity;

        if (quantityAfterAddition > product.getStock()) {

            throw new IllegalArgumentException(
                    product.getName()
                            + "の在庫は"
                            + product.getStock()
                            + "個です。");
        }

        CartItem item = new CartItem(
                product.getId(),
                product.getName(),
                product.getPrice(),
                quantity);

        cart.addItem(item);
    }

    public void updateQuantity(
            Cart cart,
            Long productId,
            int quantity) {

        Product product = productService.findById(productId);

        if (quantity > product.getStock()) {

            throw new IllegalArgumentException(
                    product.getName()
                            + "の在庫は"
                            + product.getStock()
                            + "個です。");
        }

        cart.updateQuantity(
                productId,
                quantity);
    }
}