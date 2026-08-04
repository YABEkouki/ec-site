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

        CartItem item = new CartItem(
                product.getId(),
                product.getName(),
                product.getPrice(),
                quantity);

        cart.addItem(item);
    }
}