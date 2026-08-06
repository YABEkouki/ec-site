package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.cart.CartItem;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Product;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

        @Mock
        private OrderRepository orderRepository;

        @Mock
        private ProductService productService;

        private OrderService orderService;

        @BeforeEach
        void setUp() {
                orderService = new OrderService(
                                orderRepository,
                                productService);
        }

        @Test
        void createOrderSavesOrderAndReducesStock() {

                Product product = createProduct(
                                1L,
                                "テスト商品",
                                1000,
                                5);

                Cart cart = new Cart();
                cart.addItem(new CartItem(
                                1L,
                                "テスト商品",
                                1000,
                                2));

                when(productService.findByIdForUpdate(1L))
                                .thenReturn(product);

                when(orderRepository.save(any(Order.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                Order order = orderService.createOrder(
                                10L,
                                cart, createCheckoutForm());

                assertEquals(2000, order.getTotalAmount());
                assertEquals(1, order.getItems().size());
                assertEquals(3, product.getStock());

                verify(orderRepository).save(order);
        }

        @Test
        void createOrderRejectsChangedPriceAndRefreshesCart() {

                Product product = createProduct(
                                1L,
                                "テスト商品",
                                1200,
                                5);

                Cart cart = new Cart();
                cart.addItem(new CartItem(
                                1L,
                                "テスト商品",
                                1000,
                                1));

                when(productService.findByIdForUpdate(1L))
                                .thenReturn(product);

                OrderValidationException exception = assertThrows(
                                OrderValidationException.class,
                                () -> orderService.createOrder(
                                                10L,
                                                cart, 
                                                createCheckoutForm()));

                assertEquals(
                                "テスト商品の価格が1000円から1200円に変更されました。"
                                                + "カートを確認してください。",
                                exception.getMessage());

                assertEquals(
                                1200,
                                cart.getItems().get(0).getPrice());

                verify(orderRepository, never())
                                .save(any(Order.class));
        }

        @Test
        void createOrderRejectsInsufficientStock() {

                Product product = createProduct(
                                1L,
                                "テスト商品",
                                1000,
                                1);

                Cart cart = new Cart();
                cart.addItem(new CartItem(
                                1L,
                                "テスト商品",
                                1000,
                                2));

                when(productService.findByIdForUpdate(1L))
                                .thenReturn(product);

                OrderValidationException exception = assertThrows(
                                OrderValidationException.class,
                                () -> orderService.createOrder(
                                                10L,
                                                cart,
                                                createCheckoutForm()));

                assertEquals(
                                "テスト商品の在庫が不足しています。",
                                exception.getMessage());

                assertEquals(1, product.getStock());

                verify(orderRepository, never())
                                .save(any(Order.class));
        }

        @Test
        void createOrderRejectsUnavailableProduct() {

                Cart cart = new Cart();
                cart.addItem(new CartItem(
                                1L,
                                "販売終了商品",
                                1000,
                                1));

                when(productService.findByIdForUpdate(1L))
                                .thenThrow(
                                                new ProductNotFoundException(1L));

                OrderValidationException exception = assertThrows(
                                OrderValidationException.class,
                                () -> orderService.createOrder(
                                                10L,
                                                cart,
                                                createCheckoutForm()));

                assertEquals(
                                "販売終了商品は現在購入できません。",
                                exception.getMessage());

                assertEquals(1, cart.getItems().size());

                verify(orderRepository, never())
                                .save(any(Order.class));
        }

        private Product createProduct(
                        Long id,
                        String name,
                        int price,
                        int stock) {

                Product product = new Product();
                product.setId(id);
                product.setName(name);
                product.setPrice(price);
                product.setStock(stock);

                return product;
        }

        private CheckoutForm createCheckoutForm() {

                CheckoutForm form = new CheckoutForm();

                form.setShippingName("山田 太郎");
                form.setShippingPostalCode("123-4567");
                form.setShippingPrefecture("東京都");
                form.setShippingCity("千代田区");
                form.setShippingAddressLine("1-2-3");
                form.setShippingPhone("090-1234-5678");

                return form;
        }

        @Test
    void findOrdersByUserIdUsesSpecifiedPagingConditions() {

        Long userId = 10L;
        int page = 1;
        int size = 2;

        Pageable expectedPageable =
                PageRequest.of(page, size);

        Page<Order> expectedPage =
                new PageImpl<>(
                        List.of(
                                new Order(userId, 1000),
                                new Order(userId, 2000)),
                        expectedPageable,
                        5);

        when(orderRepository
                .findByUserIdOrderByOrderedAtDesc(
                        userId,
                        expectedPageable))
                .thenReturn(expectedPage);

        OrderService orderService =
                new OrderService(
                        orderRepository,
                        productService);

        Page<Order> actualPage =
                orderService.findOrdersByUserId(
                        userId,
                        page,
                        size);

        assertSame(expectedPage, actualPage);

        verify(orderRepository)
                .findByUserIdOrderByOrderedAtDesc(
                        userId,
                        expectedPageable);
    }
}
