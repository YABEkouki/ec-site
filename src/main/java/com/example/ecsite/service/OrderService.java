package com.example.ecsite.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.entity.Product;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.repository.OrderRepository;

@Service
public class OrderService {

        private final OrderRepository orderRepository;
        private final ProductService productService;

        public OrderService(
                        OrderRepository orderRepository,
                        ProductService productService) {

                this.orderRepository = orderRepository;
                this.productService = productService;
        }

        @Transactional
        public Order createOrder(Long userId, Cart cart, CheckoutForm checkoutForm) {

                if (cart.getItems().isEmpty()) {
                        throw new OrderValidationException(
                                        "カートに商品がありません。");
                }

                Order order = new Order(userId, 0);

                order.setShippingAddress(
                                checkoutForm.getShippingName().trim(),
                                checkoutForm.getShippingPostalCode().trim(),
                                checkoutForm.getShippingPrefecture().trim(),
                                checkoutForm.getShippingCity().trim(),
                                checkoutForm.getShippingAddressLine().trim(),
                                checkoutForm.getShippingPhone().trim());

                int totalAmount = 0;

                for (com.example.ecsite.cart.CartItem cartItem : cart.getItems()) {

                        Product product;

                        try {
                                product = productService.findByIdForUpdate(
                                                cartItem.getProductId());

                        } catch (ProductNotFoundException e) {
                                throw new OrderValidationException(
                                                cartItem.getProductName()
                                                                + "は現在購入できません。",
                                                e);
                        }

                        if (product.getPrice() != cartItem.getPrice()) {

                                int oldPrice = cartItem.getPrice();
                                int newPrice = product.getPrice();

                                cart.refreshPrice(
                                                cartItem.getProductId(),
                                                newPrice);

                                throw new OrderValidationException(
                                                product.getName()
                                                                + "の価格が"
                                                                + oldPrice
                                                                + "円から"
                                                                + newPrice
                                                                + "円に変更されました。"
                                                                + "カートを確認してください。");
                        }

                        if (product.getStock() < cartItem.getQuantity()) {
                                throw new OrderValidationException(
                                                product.getName()
                                                                + "の在庫が不足しています。");
                        }

                        OrderItem orderItem = new OrderItem(
                                        product.getId(),
                                        product.getName(),
                                        product.getPrice(),
                                        cartItem.getQuantity());

                        order.addItem(orderItem);

                        totalAmount += orderItem.getSubtotal();

                        product.setStock(
                                        product.getStock()
                                                        - cartItem.getQuantity());
                }

                order.setTotalAmount(totalAmount);

                return orderRepository.save(order);
        }

        @Transactional(readOnly = true)
        public Page<Order> findOrdersByUserId(Long userId, int page, int size) {

                Pageable pageable = PageRequest.of(page, size);

                return orderRepository.findByUserIdOrderByOrderedAtDesc(userId, pageable);
        }

        @Transactional(readOnly = true)
        public void validateCart(Cart cart) {

                if (cart.getItems().isEmpty()) {
                        throw new OrderValidationException(
                                        "カートに商品がありません。");
                }

                List<String> messages = new ArrayList<>();

                for (com.example.ecsite.cart.CartItem cartItem : cart.getItems()) {

                        Product product;

                        try {
                                product = productService.findById(
                                                cartItem.getProductId());

                        } catch (ProductNotFoundException e) {
                                messages.add(
                                                cartItem.getProductName()
                                                                + "は現在購入できません。");
                                continue;
                        }

                        if (product.getPrice() != cartItem.getPrice()) {

                                int oldPrice = cartItem.getPrice();
                                int newPrice = product.getPrice();

                                cart.refreshPrice(
                                                cartItem.getProductId(),
                                                newPrice);

                                messages.add(
                                                product.getName()
                                                                + "の価格が"
                                                                + oldPrice
                                                                + "円から"
                                                                + newPrice
                                                                + "円に変更されました。");
                        }

                        if (product.getStock() < cartItem.getQuantity()) {

                                messages.add(
                                                product.getName()
                                                                + "の在庫が不足しています。");
                        }
                }

                if (!messages.isEmpty()) {
                        throw new OrderValidationException(
                                        String.join(" ", messages));
                }
        }

        @Transactional(readOnly = true)
        public Page<Order> findAllOrders(
                        int page,
                        int size) {

                Pageable pageable = PageRequest.of(
                                page,
                                size,
                                Sort.by("orderedAt").descending());

                return orderRepository.findAll(pageable);
        }

        @Transactional(readOnly = true)
        public Order findOrderWithItems(Long id) {

                return orderRepository.findByIdWithItems(id)
                                .orElseThrow(() -> new OrderNotFoundException(id));
        }

        @Transactional
        public void markAsPaid(Long id) {

                Order order = findOrderForUpdate(id);
                order.markAsPaid();
        }

        @Transactional
        public void markAsShipped(Long id) {

                Order order = findOrderForUpdate(id);
                order.markAsShipped();
        }

        @Transactional
        public void cancelOrder(Long id) {

                Order order = findOrderForUpdate(id);
                order.cancel();
        }

        private Order findOrderForUpdate(Long id) {

                return orderRepository.findById(id)
                                .orElseThrow(() -> new OrderNotFoundException(id));
        }

        @Transactional(readOnly = true)
        public Order findOrderByIdAndUserId(
                        Long orderId,
                        Long userId) {

                return orderRepository
                                .findByIdAndUserIdWithItems(
                                                orderId,
                                                userId)
                                .orElseThrow(() -> new OrderNotFoundException(orderId));
        }
}