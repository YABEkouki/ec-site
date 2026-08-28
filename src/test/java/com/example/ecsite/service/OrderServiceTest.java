package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

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
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.Product;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
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

        @Mock
        private InventoryService inventoryService;

        private OrderService orderService;

        @BeforeEach
        void setUp() {
                orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);
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

                Long orderId = 100L;

                when(orderRepository.save(any(Order.class)))
                                .thenAnswer(invocation -> {
                                        Order order = invocation.getArgument(0);

                                        org.springframework.test.util.ReflectionTestUtils
                                                        .setField(order, "id", orderId);

                                        return order;
                                });

                Order order = orderService.createOrder(
                                10L,
                                cart, createCheckoutForm());

                assertEquals(2000, order.getTotalAmount());
                assertEquals(1, order.getItems().size());
                verify(inventoryService)
                                .decreaseForOrder(
                                                1L,
                                                2,
                                                orderId);

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

                Pageable expectedPageable = PageRequest.of(page, size);

                Page<Order> expectedPage = new PageImpl<>(
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

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                Page<Order> actualPage = orderService.findOrdersByUserId(
                                userId,
                                page,
                                size);

                assertSame(expectedPage, actualPage);

                verify(orderRepository)
                                .findByUserIdOrderByOrderedAtDesc(
                                                userId,
                                                expectedPageable);
        }

        @Test
        void cancelOrderRestoresProductStock() {

                Long orderId = 1L;
                Long productId = 10L;
                int quantity = 3;

                Order order = mock(Order.class);
                OrderItem orderItem = mock(OrderItem.class);

                when(order.getId())
                                .thenReturn(orderId);

                when(orderRepository.findByIdForUpdate(orderId))
                                .thenReturn(Optional.of(order));

                when(order.getItems())
                                .thenReturn(List.of(orderItem));

                when(orderItem.getProductId())
                                .thenReturn(productId);

                when(orderItem.getQuantity())
                                .thenReturn(quantity);

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                orderService.cancelOrder(orderId);

                verify(order).cancel();

                verify(inventoryService)
                                .restoreForOrderCancellation(
                                                productId,
                                                quantity,
                                                orderId);
        }

        @Test
        void cancelOrderThrowsExceptionWhenOrderDoesNotExist() {

                Long orderId = 999L;

                when(orderRepository.findByIdForUpdate(orderId))
                                .thenReturn(Optional.empty());

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                assertThrows(
                                OrderNotFoundException.class,
                                () -> orderService.cancelOrder(orderId));

                verifyNoInteractions(productService);
        }

        @Test
        void paidOrderCannotBeCancelledAndStockIsNotChanged() {

                Long orderId = 1L;

                Order order = new Order(10L, 1000);
                order.markAsPaid();

                when(orderRepository.findByIdForUpdate(orderId))
                                .thenReturn(Optional.of(order));

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                assertThrows(
                                InvalidOrderStatusException.class,
                                () -> orderService.cancelOrder(orderId));

                verifyNoInteractions(productService);
        }

        @Test
        void createOrderCalculatesTotalAndReducesStock() {

                Long userId = 10L;
                Long productId = 20L;

                Cart cart = mock(Cart.class);
                CartItem cartItem = mock(CartItem.class);
                Product product = mock(Product.class);
                CheckoutForm checkoutForm = createValidCheckoutForm();

                when(cart.getItems())
                                .thenReturn(List.of(cartItem));

                when(cartItem.getProductId())
                                .thenReturn(productId);

                when(cartItem.getPrice())
                                .thenReturn(1000);

                when(cartItem.getQuantity())
                                .thenReturn(3);

                when(productService.findByIdForUpdate(productId))
                                .thenReturn(product);

                when(product.getId())
                                .thenReturn(productId);

                when(product.getName())
                                .thenReturn("テスト商品");

                when(product.getPrice())
                                .thenReturn(1000);

                when(product.getStock())
                                .thenReturn(10);

                Long orderId = 100L;

                when(orderRepository.save(any(Order.class)))
                                .thenAnswer(invocation -> {
                                        Order order = invocation.getArgument(0);

                                        org.springframework.test.util.ReflectionTestUtils
                                                        .setField(order, "id", orderId);

                                        return order;
                                });

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                Order result = orderService.createOrder(
                                userId,
                                cart,
                                checkoutForm);

                assertEquals(3000, result.getTotalAmount());
                assertEquals(1, result.getItems().size());
                assertEquals(3, result.getItems().get(0).getQuantity());

                verify(inventoryService)
                                .decreaseForOrder(
                                                productId,
                                                3,
                                                orderId);
                verify(orderRepository).save(result);
        }

        @Test
        void createOrderRejectsEmptyCart() {

                Cart cart = mock(Cart.class);
                CheckoutForm checkoutForm = mock(CheckoutForm.class);

                when(cart.getItems())
                                .thenReturn(List.of());

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                OrderValidationException exception = assertThrows(
                                OrderValidationException.class,
                                () -> orderService.createOrder(
                                                10L,
                                                cart,
                                                checkoutForm));

                assertEquals(
                                "カートに商品がありません。",
                                exception.getMessage());

                verifyNoInteractions(productService);
                verifyNoInteractions(orderRepository);
        }

        private CheckoutForm createValidCheckoutForm() {

                CheckoutForm checkoutForm = mock(CheckoutForm.class);

                when(checkoutForm.getShippingName())
                                .thenReturn("山田 太郎");

                when(checkoutForm.getShippingPostalCode())
                                .thenReturn("100-0001");

                when(checkoutForm.getShippingPrefecture())
                                .thenReturn("東京都");

                when(checkoutForm.getShippingCity())
                                .thenReturn("千代田区");

                when(checkoutForm.getShippingAddressLine())
                                .thenReturn("千代田1-1");

                when(checkoutForm.getShippingPhone())
                                .thenReturn("090-1234-5678");

                return checkoutForm;
        }

        @Test
        void createOrderRejectsChangedProductPrice() {

                Long userId = 10L;
                Long productId = 20L;

                Cart cart = mock(Cart.class);
                CartItem cartItem = mock(CartItem.class);
                Product product = mock(Product.class);

                CheckoutForm checkoutForm = createValidCheckoutForm();

                when(cart.getItems())
                                .thenReturn(List.of(cartItem));

                when(cartItem.getProductId())
                                .thenReturn(productId);

                when(cartItem.getPrice())
                                .thenReturn(1000);

                when(productService.findByIdForUpdate(productId))
                                .thenReturn(product);

                when(product.getName())
                                .thenReturn("テスト商品");

                when(product.getPrice())
                                .thenReturn(1200);

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                OrderValidationException exception = assertThrows(
                                OrderValidationException.class,
                                () -> orderService.createOrder(
                                                userId,
                                                cart,
                                                checkoutForm));

                assertEquals(
                                "テスト商品の価格が1000円から1200円に変更されました。"
                                                + "カートを確認してください。",
                                exception.getMessage());

                verify(cart).refreshPrice(
                                productId,
                                1200);

                verifyNoInteractions(orderRepository);
        }

        @Test
        void createOrderRejectsInsufficientStockSecond() {

                Long userId = 10L;
                Long productId = 20L;

                Cart cart = mock(Cart.class);
                CartItem cartItem = mock(CartItem.class);
                Product product = mock(Product.class);

                CheckoutForm checkoutForm = createValidCheckoutForm();

                when(cart.getItems())
                                .thenReturn(List.of(cartItem));

                when(cartItem.getProductId())
                                .thenReturn(productId);

                when(cartItem.getPrice())
                                .thenReturn(1000);

                when(cartItem.getQuantity())
                                .thenReturn(3);

                when(productService.findByIdForUpdate(productId))
                                .thenReturn(product);

                when(product.getName())
                                .thenReturn("テスト商品");

                when(product.getPrice())
                                .thenReturn(1000);

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                OrderValidationException exception = assertThrows(
                                OrderValidationException.class,
                                () -> orderService.createOrder(
                                                userId,
                                                cart,
                                                checkoutForm));

                assertEquals(
                                "テスト商品の在庫が不足しています。",
                                exception.getMessage());

                verifyNoInteractions(orderRepository);
        }

        @Test
        void createOrderRejectsUnavailableProductSecond() {

                Long userId = 10L;
                Long productId = 20L;

                Cart cart = mock(Cart.class);
                CartItem cartItem = mock(CartItem.class);

                CheckoutForm checkoutForm = createValidCheckoutForm();

                when(cart.getItems())
                                .thenReturn(List.of(cartItem));

                when(cartItem.getProductId())
                                .thenReturn(productId);

                when(cartItem.getProductName())
                                .thenReturn("販売終了商品");

                when(productService.findByIdForUpdate(productId))
                                .thenThrow(
                                                new ProductNotFoundException(productId));

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                OrderValidationException exception = assertThrows(
                                OrderValidationException.class,
                                () -> orderService.createOrder(
                                                userId,
                                                cart,
                                                checkoutForm));

                assertEquals(
                                "販売終了商品は現在購入できません。",
                                exception.getMessage());

                verifyNoInteractions(orderRepository);
        }

        @Test
        void findAllOrdersFiltersByStatus() {

                OrderStatus status = OrderStatus.ORDERED;
                int page = 1;
                int size = 10;

                Pageable pageable = PageRequest.of(page, size);

                Page<Order> expectedPage = new PageImpl<>(
                                List.of(),
                                pageable,
                                0);

                when(orderRepository
                                .findByStatusOrderByOrderedAtDesc(
                                                status,
                                                pageable))
                                .thenReturn(expectedPage);

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                Page<Order> actualPage = orderService.findAllOrders(
                                status,
                                page,
                                size);

                assertSame(expectedPage, actualPage);

                verify(orderRepository)
                                .findByStatusOrderByOrderedAtDesc(
                                                status,
                                                pageable);
        }

        @Test
        void findAllOrdersReturnsAllOrdersWhenStatusIsNull() {

                int page = 0;
                int size = 10;

                Pageable pageable = PageRequest.of(page, size);

                Page<Order> expectedPage = new PageImpl<>(
                                List.of(),
                                pageable,
                                0);

                when(orderRepository
                                .findAllByOrderByOrderedAtDesc(
                                                pageable))
                                .thenReturn(expectedPage);

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                Page<Order> actualPage = orderService.findAllOrders(
                                null,
                                page,
                                size);

                assertSame(expectedPage, actualPage);

                verify(orderRepository)
                                .findAllByOrderByOrderedAtDesc(
                                                pageable);
        }

        @Test
        void cancelOrderForUserRestoresStockForOwnedOrder() {

                Long orderId = 1L;
                Long userId = 10L;
                Long productId = 20L;
                int quantity = 3;

                Order order = mock(Order.class);
                OrderItem orderItem = mock(OrderItem.class);

                when(order.getId())
                                .thenReturn(orderId);

                when(orderRepository
                                .findByIdAndUserIdForUpdate(
                                                orderId,
                                                userId))
                                .thenReturn(Optional.of(order));

                when(order.getItems())
                                .thenReturn(List.of(orderItem));

                when(orderItem.getProductId())
                                .thenReturn(productId);

                when(orderItem.getQuantity())
                                .thenReturn(quantity);

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                orderService.cancelOrderForUser(
                                orderId,
                                userId);

                verify(order).cancel();

                verify(inventoryService)
                                .restoreForOrderCancellation(
                                                productId,
                                                quantity,
                                                orderId);
        }

        @Test
        void cancelOrderForUserRestoresStockForOwnedOrderSecond() {

                Long orderId = 1L;
                Long userId = 10L;
                Long productId = 20L;
                int quantity = 3;

                Order order = mock(Order.class);
                OrderItem orderItem = mock(OrderItem.class);

                when(order.getId())
                                .thenReturn(orderId);

                when(orderRepository
                                .findByIdAndUserIdForUpdate(
                                                orderId,
                                                userId))
                                .thenReturn(Optional.of(order));

                when(order.getItems())
                                .thenReturn(List.of(orderItem));

                when(orderItem.getProductId())
                                .thenReturn(productId);

                when(orderItem.getQuantity())
                                .thenReturn(quantity);

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                orderService.cancelOrderForUser(
                                orderId,
                                userId);

                verify(order).cancel();

                verify(inventoryService)
                                .restoreForOrderCancellation(
                                                productId,
                                                quantity,
                                                orderId);
        }

        @Test
        void findOrderByIdAndUserIdReturnsOwnedOrder() {

                Long orderId = 1L;
                Long userId = 10L;

                Order expectedOrder = new Order(userId, 2000);

                when(orderRepository
                                .findByIdAndUserIdWithItems(
                                                orderId,
                                                userId))
                                .thenReturn(Optional.of(expectedOrder));

                Order actualOrder = orderService.findOrderByIdAndUserId(
                                orderId,
                                userId);

                assertSame(expectedOrder, actualOrder);

                verify(orderRepository)
                                .findByIdAndUserIdWithItems(
                                                orderId,
                                                userId);
        }

        @Test
        void countOrdersByStatusReturnsRepositoryCount() {

                OrderStatus status = OrderStatus.ORDERED;

                when(orderRepository.countByStatus(status))
                                .thenReturn(5L);

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                long actualCount = orderService.countOrdersByStatus(status);

                assertEquals(5L, actualCount);

                verify(orderRepository)
                                .countByStatus(status);
        }

        @Test
        void findOrderByIdAndUserIdThrowsExceptionWhenOrderIsNotFound() {

                Long orderId = 1L;
                Long userId = 10L;

                when(orderRepository
                                .findByIdAndUserIdWithItems(
                                                orderId,
                                                userId))
                                .thenReturn(Optional.empty());

                assertThrows(
                                OrderNotFoundException.class,
                                () -> orderService.findOrderByIdAndUserId(
                                                orderId,
                                                userId));

                verify(orderRepository)
                                .findByIdAndUserIdWithItems(
                                                orderId,
                                                userId);
        }

        @Test
        void createOrderDecreasesStockThroughInventoryServiceWithSavedOrderId() {

                Long userId = 10L;
                Long productId = 1L;
                Long orderId = 100L;

                Product product = createProduct(
                                productId,
                                "テスト商品",
                                1000,
                                5);

                Cart cart = new Cart();
                cart.addItem(new CartItem(
                                productId,
                                "テスト商品",
                                1000,
                                2));

                when(productService.findByIdForUpdate(productId))
                                .thenReturn(product);

                when(orderRepository.save(any(Order.class)))
                                .thenAnswer(invocation -> {

                                        Order order = invocation.getArgument(0);

                                        org.springframework.test.util.ReflectionTestUtils
                                                        .setField(order, "id", orderId);

                                        return order;
                                });

                OrderService orderService = new OrderService(
                                orderRepository,
                                productService,
                                inventoryService);

                Order order = orderService.createOrder(
                                userId,
                                cart,
                                createCheckoutForm());

                assertEquals(orderId, order.getId());

                verify(inventoryService)
                                .decreaseForOrder(
                                                productId,
                                                2,
                                                orderId);
        }
}
