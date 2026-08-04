package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.entity.Product;
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
        public Order createOrder(Long userId, Cart cart) {

                if (cart.getItems().isEmpty()) {
                        throw new IllegalStateException(
                                        "カートに商品がありません。");
                }

                Order order = new Order(userId, 0);
                int totalAmount = 0;

                for (com.example.ecsite.cart.CartItem cartItem : cart.getItems()) {

                        Product product = productService.findById(
                                        cartItem.getProductId());

                        if (product.getStock() < cartItem.getQuantity()) {
                                throw new IllegalStateException(
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
        public List<Order> findOrdersByUserId(Long userId) {
                return orderRepository
                                .findByUserIdOrderByOrderedAtDesc(userId);
        }
}