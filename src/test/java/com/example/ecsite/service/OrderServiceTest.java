package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.cart.CartItem;
import com.example.ecsite.dto.ActionRequiredAgingSummary;
import com.example.ecsite.dto.AdminActionRequiredOrderDto;
import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.entity.Category;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.entity.Product;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.form.ActionRequiredOrderSort;
import com.example.ecsite.form.AdminActionRequiredOrderSearchForm;
import com.example.ecsite.form.AdminOrderAssigneeFilter;
import com.example.ecsite.form.AdminOrderSearchForm;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.projection.ActionRequiredAgingSummaryProjection;
import com.example.ecsite.repository.projection.AdminActionRequiredOrderSearchProjection;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductService productService;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private OrderStatusHistoryService orderStatusHistoryService;

    @Mock
    private OrderHandlingStatusHistoryService orderHandlingStatusHistoryService;

    @Mock
    private AdminAccountService adminAccountService;

    @Mock
    private OrderAssigneeHistoryService orderAssigneeHistoryService;

    private OrderService orderService;

    private static final List<OrderHandlingStatus> ALL_HANDLING_STATUSES = List.of(OrderHandlingStatus.values());

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository,
                productService,
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);
    }

    @Test
    void createOrderSavesOrderAndReducesStock() {

        Long userId = 10L;
        String username = "testuser";

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
                userId,
                username,
                cart,
                createCheckoutForm());

        assertEquals(2000, order.getTotalAmount());
        assertEquals(1, order.getItems().size());
        assertEquals(100L, order.getItems().get(0).getCategoryId());
        assertEquals("テストカテゴリ", order.getItems().get(0).getCategoryName());

        verify(inventoryService)
                .decreaseForOrder(
                        1L,
                        2,
                        orderId);

        verify(orderRepository).save(order);

        verify(orderStatusHistoryService)
                .record(
                        order,
                        null,
                        OrderStatus.ORDERED,
                        OrderStatusHistoryActorType.USER,
                        userId,
                        username);
    }

    @Test
    void createOrderRejectsChangedPriceAndRefreshesCart() {

        String username = "testuser";

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
                        username,
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

        String username = "testuser";

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
                        username,
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

        String username = "testuser";

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
                        username,
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

        Category category = new Category("テストカテゴリ");

        org.springframework.test.util.ReflectionTestUtils
                .setField(category, "id", 100L);

        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setPrice(price);
        product.setStock(stock);
        product.setCategory(category);

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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

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

        Long adminId = 20L;
        String adminUsername = "admin";
        String internalNote = "お客様から電話でキャンセル依頼";

        Order order = mock(Order.class);
        OrderItem orderItem = mock(OrderItem.class);

        when(order.getId())
                .thenReturn(orderId);

        when(order.getStatus())
                .thenReturn(
                        OrderStatus.ORDERED,
                        OrderStatus.CANCELLED);

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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        orderService.cancelOrder(
                orderId,
                adminId,
                adminUsername,
                internalNote);

        verify(order).cancel();

        verify(inventoryService)
                .restoreForOrderCancellation(
                        productId,
                        quantity,
                        orderId);

        verify(orderStatusHistoryService)
                .record(
                        order,
                        OrderStatus.ORDERED,
                        OrderStatus.CANCELLED,
                        OrderStatusHistoryActorType.ADMIN,
                        adminId,
                        adminUsername,
                        internalNote);
    }

    @Test
    void cancelOrderThrowsExceptionWhenOrderDoesNotExist() {

        Long orderId = 999L;

        Long adminId = 20L;
        String adminUsername = "admin";

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.empty());

        OrderService orderService = new OrderService(
                orderRepository,
                productService,
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        assertThrows(
                OrderNotFoundException.class,
                () -> orderService.cancelOrder(
                        orderId,
                        adminId,
                        adminUsername,
                        null));

        verifyNoInteractions(productService);
    }

    @Test
    void paidOrderCannotBeCancelledAndStockIsNotChanged() {

        Long orderId = 1L;

        Long adminId = 20L;
        String adminUsername = "admin";

        Order order = new Order(10L, 1000);
        order.markAsPaid();

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        OrderService orderService = new OrderService(
                orderRepository,
                productService,
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.cancelOrder(
                        orderId,
                        adminId,
                        adminUsername,
                        null));

        verifyNoInteractions(productService);

        verifyNoInteractions(orderStatusHistoryService);
    }

    @Test
    void createOrderCalculatesTotalAndReducesStock() {

        Long userId = 10L;
        String username = "testuser";
        Long productId = 20L;

        Cart cart = mock(Cart.class);
        CartItem cartItem = mock(CartItem.class);
        Product product = mock(Product.class);
        Category category = mock(Category.class);
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

        when(product.getCategory())
                .thenReturn(category);

        when(category.getId())
                .thenReturn(100L);

        when(category.getName())
                .thenReturn("テストカテゴリ");

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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        Order result = orderService.createOrder(
                userId,
                username,
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

        String username = "testuser";

        Cart cart = mock(Cart.class);
        CheckoutForm checkoutForm = mock(CheckoutForm.class);

        when(cart.getItems())
                .thenReturn(List.of());

        OrderService orderService = new OrderService(
                orderRepository,
                productService,
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        OrderValidationException exception = assertThrows(
                OrderValidationException.class,
                () -> orderService.createOrder(
                        10L,
                        username,
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
        String username = "testuser";
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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        OrderValidationException exception = assertThrows(
                OrderValidationException.class,
                () -> orderService.createOrder(
                        userId,
                        username,
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
        String username = "testuser";
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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        OrderValidationException exception = assertThrows(
                OrderValidationException.class,
                () -> orderService.createOrder(
                        userId,
                        username,
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
        String username = "testuser";
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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        OrderValidationException exception = assertThrows(
                OrderValidationException.class,
                () -> orderService.createOrder(
                        userId,
                        username,
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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

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
        String username = "testuser";
        Long productId = 20L;
        int quantity = 3;

        Order order = mock(Order.class);
        OrderItem orderItem = mock(OrderItem.class);

        when(order.getId())
                .thenReturn(orderId);

        when(order.getStatus())
                .thenReturn(
                        OrderStatus.ORDERED,
                        OrderStatus.CANCELLED);

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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        orderService.cancelOrderForUser(
                orderId,
                userId,
                username);

        verify(order).cancel();

        verify(inventoryService)
                .restoreForOrderCancellation(
                        productId,
                        quantity,
                        orderId);

        verify(orderStatusHistoryService)
                .record(
                        order,
                        OrderStatus.ORDERED,
                        OrderStatus.CANCELLED,
                        OrderStatusHistoryActorType.USER,
                        userId,
                        username,
                        null);
    }

    @Test
    void cancelOrderForUserRestoresStockForOwnedOrderSecond() {

        Long orderId = 1L;
        Long userId = 10L;
        String username = "testuser";
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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        orderService.cancelOrderForUser(
                orderId,
                userId,
                username);

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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        long actualCount = orderService.countOrdersByStatus(status);

        assertEquals(5L, actualCount);

        verify(orderRepository)
                .countByStatus(status);
    }

    @Test
    void countOrdersByHandlingStatusReturnsRepositoryCount() {

        when(orderRepository.countByHandlingStatus(
                OrderHandlingStatus.NEEDS_ACTION))
                .thenReturn(3L);

        long result = orderService.countOrdersByHandlingStatus(
                OrderHandlingStatus.NEEDS_ACTION);

        assertEquals(3L, result);
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
        String username = "testuser";
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
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderAssigneeHistoryService,
                adminAccountService);

        Order order = orderService.createOrder(
                userId,
                username,
                cart,
                createCheckoutForm());

        assertEquals(orderId, order.getId());

        verify(inventoryService)
                .decreaseForOrder(
                        productId,
                        2,
                        orderId);
    }

    @Test
    void searchOrdersConvertsDateRangeAndCallsRepository() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();
        searchForm.setOrderId(123L);
        searchForm.setUserId(456L);
        searchForm.setFrom(LocalDate.of(2026, 8, 10));
        searchForm.setTo(LocalDate.of(2026, 8, 20));
        searchForm.setStatus(OrderStatus.PAID);

        Page<Order> expected = new PageImpl<>(List.of());

        when(orderRepository.search(
                123L,
                456L,
                LocalDateTime.of(2026, 8, 10, 0, 0),
                LocalDateTime.of(2026, 8, 21, 0, 0),
                OrderStatus.PAID,
                ALL_HANDLING_STATUSES,
                "ALL",
                null,
                PageRequest.of(2, 20)))
                .thenReturn(expected);

        Page<Order> actual = orderService.searchOrders(
                searchForm,
                1L,
                2,
                20);

        assertSame(expected, actual);

        verify(orderRepository).search(
                123L,
                456L,
                LocalDateTime.of(2026, 8, 10, 0, 0),
                LocalDateTime.of(2026, 8, 21, 0, 0),
                OrderStatus.PAID,
                ALL_HANDLING_STATUSES,
                "ALL",
                null,
                PageRequest.of(2, 20));
    }

    @Test
    void searchOrdersUsesDefaultDateRangeWhenDatesAreNotSpecified() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();

        Page<Order> expected = new PageImpl<>(List.of());

        when(orderRepository.search(
                null,
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                "ALL",
                null,
                PageRequest.of(0, 10)))
                .thenReturn(expected);

        Page<Order> actual = orderService.searchOrders(
                searchForm,
                1L,
                0,
                10);

        assertSame(expected, actual);

        verify(orderRepository).search(
                null,
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                "ALL",
                null,
                PageRequest.of(0, 10));
    }

    @Test
    void searchOrdersFiltersByUnassignedAssignee() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();
        searchForm.setAssigneeFilter(
                AdminOrderAssigneeFilter.UNASSIGNED);

        Page<Order> expected = new PageImpl<>(List.of());

        when(orderRepository.search(
                null,
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                "UNASSIGNED",
                null,
                PageRequest.of(0, 10)))
                .thenReturn(expected);

        Page<Order> actual = orderService.searchOrders(
                searchForm,
                20L,
                0,
                10);

        assertSame(expected, actual);

        verify(orderRepository).search(
                null,
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                "UNASSIGNED",
                null,
                PageRequest.of(0, 10));
    }

    @Test
    void searchOrdersFiltersByLoginAdminAssignee() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();
        searchForm.setAssigneeFilter(
                AdminOrderAssigneeFilter.ME);

        Page<Order> expected = new PageImpl<>(List.of());

        when(orderRepository.search(
                null,
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                "ME",
                20L,
                PageRequest.of(0, 10)))
                .thenReturn(expected);

        Page<Order> actual = orderService.searchOrders(
                searchForm,
                20L,
                0,
                10);

        assertSame(expected, actual);

        verify(orderRepository).search(
                null,
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                "ME",
                20L,
                PageRequest.of(0, 10));
    }

    @Test
    void searchOrdersFiltersBySpecifiedAdminAssignee() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();
        searchForm.setAssigneeFilter(
                AdminOrderAssigneeFilter.SPECIFIC);
        searchForm.setAssignedAdminAccountId(30L);

        Page<Order> expected = new PageImpl<>(List.of());

        when(orderRepository.search(
                null,
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                "SPECIFIC",
                30L,
                PageRequest.of(0, 10)))
                .thenReturn(expected);

        Page<Order> actual = orderService.searchOrders(
                searchForm,
                20L,
                0,
                10);

        assertSame(expected, actual);

        verify(orderRepository).search(
                null,
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                "SPECIFIC",
                30L,
                PageRequest.of(0, 10));
    }

    @Test
    void searchAllOrdersUsesUnpagedSearch() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();
        searchForm.setOrderId(100L);
        searchForm.setUserId(10L);
        searchForm.setFrom(LocalDate.of(2026, 8, 1));
        searchForm.setTo(LocalDate.of(2026, 8, 31));
        searchForm.setStatus(OrderStatus.PAID);

        Order firstOrder = new Order(10L, 1000);
        Order secondOrder = new Order(10L, 2000);

        Page<Order> expectedPage = new PageImpl<>(
                List.of(firstOrder, secondOrder));

        when(orderRepository.search(
                100L,
                10L,
                LocalDateTime.of(2026, 8, 1, 0, 0),
                LocalDateTime.of(2026, 9, 1, 0, 0),
                OrderStatus.PAID,
                ALL_HANDLING_STATUSES,
                "ALL",
                null,
                Pageable.unpaged()))
                .thenReturn(expectedPage);

        List<Order> result = orderService.searchAllOrders(
                searchForm,
                1L);

        assertEquals(
                List.of(firstOrder, secondOrder),
                result);

        verify(orderRepository).search(
                100L,
                10L,
                LocalDateTime.of(2026, 8, 1, 0, 0),
                LocalDateTime.of(2026, 9, 1, 0, 0),
                OrderStatus.PAID,
                ALL_HANDLING_STATUSES,
                "ALL",
                null,
                Pageable.unpaged());
    }

    @Test
    void searchAllOrdersFiltersBySpecifiedAdminAssignee() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();

        searchForm.setAssigneeFilter(
                AdminOrderAssigneeFilter.SPECIFIC);

        searchForm.setAssignedAdminAccountId(30L);

        Page<Order> expectedPage = new PageImpl<>(List.of());

        when(orderRepository.search(
                null,
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                "SPECIFIC",
                30L,
                Pageable.unpaged()))
                .thenReturn(expectedPage);

        List<Order> actual = orderService.searchAllOrders(
                searchForm,
                20L);

        assertEquals(
                List.of(),
                actual);

        verify(orderRepository).search(
                null,
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 0, 0),
                null,
                ALL_HANDLING_STATUSES,
                "SPECIFIC",
                30L,
                Pageable.unpaged());
    }

    @Test
    void markAsPaidRecordsAdminStatusHistoryWithInternalNote() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";
        String internalNote = "入金確認済み";

        Order order = new Order(10L, 1000);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        orderService.markAsPaid(
                orderId,
                adminId,
                adminUsername,
                internalNote);

        assertEquals(
                OrderStatus.PAID,
                order.getStatus());

        verify(orderStatusHistoryService)
                .record(
                        order,
                        OrderStatus.ORDERED,
                        OrderStatus.PAID,
                        OrderStatusHistoryActorType.ADMIN,
                        adminId,
                        adminUsername,
                        internalNote);
    }

    @Test
    void markAsShippedRecordsAdminStatusHistoryWithInternalNote() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";
        String internalNote = "配送手配完了";

        Order order = new Order(10L, 1000);
        order.markAsPaid();

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        orderService.markAsShipped(
                orderId,
                adminId,
                adminUsername,
                internalNote);

        assertEquals(
                OrderStatus.SHIPPED,
                order.getStatus());

        verify(orderStatusHistoryService)
                .record(
                        order,
                        OrderStatus.PAID,
                        OrderStatus.SHIPPED,
                        OrderStatusHistoryActorType.ADMIN,
                        adminId,
                        adminUsername,
                        internalNote);
    }

    @Test
    void changeHandlingStatusUpdatesStatusAndRecordsHistory() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";

        Order order = new Order(10L, 1000);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        boolean changed = orderService.changeHandlingStatus(
                orderId,
                OrderHandlingStatus.NEEDS_ACTION,
                null,
                adminId,
                adminUsername);

        assertEquals(true, changed);

        assertEquals(
                OrderHandlingStatus.NEEDS_ACTION,
                order.getHandlingStatus());

        verify(orderHandlingStatusHistoryService)
                .record(
                        eq(order),
                        eq(OrderHandlingStatus.NONE),
                        eq(OrderHandlingStatus.NEEDS_ACTION),
                        eq(adminId),
                        eq(adminUsername),
                        any(UUID.class));
    }

    @Test
    void changeHandlingStatusDoesNothingWhenStatusIsUnchanged() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";

        Order order = new Order(10L, 1000);
        order.changeHandlingStatus(
                OrderHandlingStatus.NEEDS_ACTION);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        boolean changed = orderService.changeHandlingStatus(
                orderId,
                OrderHandlingStatus.NEEDS_ACTION,
                null,
                adminId,
                adminUsername);

        assertEquals(false, changed);

        assertEquals(
                OrderHandlingStatus.NEEDS_ACTION,
                order.getHandlingStatus());

        verifyNoInteractions(
                orderHandlingStatusHistoryService);

        verifyNoInteractions(
                orderAssigneeHistoryService);
    }

    @Test
    void changeHandlingStatusCanAssignAdminAtSameTime() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";
        Long assignedAdminId = 30L;

        Order order = new Order(10L, 1000);

        AdminAccount assignedAdmin = new AdminAccount();
        assignedAdmin.setUsername("admin02");
        assignedAdmin.setEnabled(true);

        org.springframework.test.util.ReflectionTestUtils
                .setField(
                        assignedAdmin,
                        "id",
                        assignedAdminId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        when(adminAccountService.findById(assignedAdminId))
                .thenReturn(assignedAdmin);

        boolean changed = orderService.changeHandlingStatus(
                orderId,
                OrderHandlingStatus.NEEDS_ACTION,
                assignedAdminId,
                adminId,
                adminUsername);

        assertEquals(true, changed);

        assertEquals(
                OrderHandlingStatus.NEEDS_ACTION,
                order.getHandlingStatus());

        assertSame(
                assignedAdmin,
                order.getAssignedAdminAccount());

        verify(orderHandlingStatusHistoryService)
                .record(
                        eq(order),
                        eq(OrderHandlingStatus.NONE),
                        eq(OrderHandlingStatus.NEEDS_ACTION),
                        eq(adminId),
                        eq(adminUsername),
                        any(UUID.class));
    }

    @Test
    void changeHandlingStatusCanChangeOnlyAssignee() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";
        Long assignedAdminId = 30L;

        Order order = new Order(10L, 1000);
        order.changeHandlingStatus(
                OrderHandlingStatus.NEEDS_ACTION);

        AdminAccount assignedAdmin = new AdminAccount();
        assignedAdmin.setUsername("admin02");
        assignedAdmin.setEnabled(true);

        org.springframework.test.util.ReflectionTestUtils
                .setField(
                        assignedAdmin,
                        "id",
                        assignedAdminId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        when(adminAccountService.findById(assignedAdminId))
                .thenReturn(assignedAdmin);

        boolean changed = orderService.changeHandlingStatus(
                orderId,
                OrderHandlingStatus.NEEDS_ACTION,
                assignedAdminId,
                adminId,
                adminUsername);

        assertEquals(true, changed);

        assertEquals(
                OrderHandlingStatus.NEEDS_ACTION,
                order.getHandlingStatus());

        assertSame(
                assignedAdmin,
                order.getAssignedAdminAccount());

        verifyNoInteractions(
                orderHandlingStatusHistoryService);

        verify(orderAssigneeHistoryService)
                .record(
                        eq(order),
                        eq(null),
                        eq(null),
                        eq(assignedAdminId),
                        eq("admin02"),
                        eq(adminId),
                        eq(adminUsername),
                        any(UUID.class));
    }

    @Test
    void changeHandlingStatusCanClearAssignee() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";

        Order order = new Order(10L, 1000);
        order.changeHandlingStatus(
                OrderHandlingStatus.IN_PROGRESS);

        AdminAccount assignedAdmin = new AdminAccount();
        assignedAdmin.setUsername("admin02");
        assignedAdmin.setEnabled(true);

        org.springframework.test.util.ReflectionTestUtils
                .setField(
                        assignedAdmin,
                        "id",
                        30L);

        order.changeAssignedAdminAccount(assignedAdmin);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        boolean changed = orderService.changeHandlingStatus(
                orderId,
                OrderHandlingStatus.IN_PROGRESS,
                null,
                adminId,
                adminUsername);

        assertEquals(true, changed);

        assertNull(
                order.getAssignedAdminAccount());

        verifyNoInteractions(
                orderHandlingStatusHistoryService);

        verify(orderAssigneeHistoryService)
                .record(
                        eq(order),
                        eq(30L),
                        eq("admin02"),
                        eq(null),
                        eq(null),
                        eq(adminId),
                        eq(adminUsername),
                        any(UUID.class));
    }

    @Test
    void searchActionRequiredOrderDetailsSearchesNeedsActionAndInProgress() {

        Long loginAdminAccountId = 20L;

        AdminActionRequiredOrderSearchForm searchForm = new AdminActionRequiredOrderSearchForm();

        AdminActionRequiredOrderSearchProjection projection = mock(AdminActionRequiredOrderSearchProjection.class);

        when(projection.getOrderId()).thenReturn(1L);
        when(projection.getHandlingStatusUpdatedAt())
                .thenReturn(LocalDateTime.now().minusDays(5));

        Pageable pageable = PageRequest.of(0, 10);

        Page<AdminActionRequiredOrderSearchProjection> projectionPage = new PageImpl<>(
                List.of(projection),
                pageable,
                1);

        when(orderRepository.searchActionRequiredOrders(
                eq(null),
                eq(null),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(null),
                eq(List.of("NEEDS_ACTION", "IN_PROGRESS")),
                eq(null),
                eq("ALL"),
                eq(null),
                eq("OLDEST"),
                eq(pageable)))
                .thenReturn(projectionPage);

        Order order = new Order(10L, 1000);

        org.springframework.test.util.ReflectionTestUtils
                .setField(order, "id", 1L);

        when(orderRepository.findAllWithAssignedAdminByIdIn(
                List.of(1L)))
                .thenReturn(List.of(order));

        Page<AdminActionRequiredOrderDto> result = orderService.searchActionRequiredOrderDetails(
                searchForm,
                loginAdminAccountId,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertSame(order, result.getContent().get(0).order());
        assertEquals(
                projection.getHandlingStatusUpdatedAt(),
                result.getContent().get(0).handlingStatusUpdatedAt());

        verify(orderRepository).searchActionRequiredOrders(
                eq(null),
                eq(null),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(null),
                eq(List.of("NEEDS_ACTION", "IN_PROGRESS")),
                eq(null),
                eq("ALL"),
                eq(null),
                eq("OLDEST"),
                eq(pageable));

        verify(orderRepository)
                .findAllWithAssignedAdminByIdIn(
                        List.of(1L));
    }

    @Test
    void searchActionRequiredOrderDetailsPassesFilterAndSortConditions() {

        Long loginAdminAccountId = 20L;

        AdminActionRequiredOrderSearchForm searchForm = new AdminActionRequiredOrderSearchForm();

        searchForm.setHandlingStatus(OrderHandlingStatus.IN_PROGRESS);
        searchForm.setMinElapsedDays(7);
        searchForm.setSort(ActionRequiredOrderSort.NEWEST);

        Pageable pageable = PageRequest.of(1, 20);

        Page<AdminActionRequiredOrderSearchProjection> projectionPage = new PageImpl<>(
                List.of(),
                pageable,
                0);

        when(orderRepository.searchActionRequiredOrders(
                eq(null),
                eq(null),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(null),
                eq(List.of("IN_PROGRESS")),
                any(LocalDateTime.class),
                eq("ALL"),
                eq(null),
                eq("NEWEST"),
                eq(pageable)))
                .thenReturn(projectionPage);

        Page<AdminActionRequiredOrderDto> result = orderService.searchActionRequiredOrderDetails(
                searchForm,
                loginAdminAccountId,
                1,
                20);

        assertEquals(0, result.getTotalElements());

        verify(orderRepository).searchActionRequiredOrders(
                eq(null),
                eq(null),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(null),
                eq(List.of("IN_PROGRESS")),
                any(LocalDateTime.class),
                eq("ALL"),
                eq(null),
                eq("NEWEST"),
                eq(pageable));
    }

    @Test
    void searchActionRequiredOrderDetailsKeepsNullUpdatedAtAsNull() {

        Long loginAdminAccountId = 20L;

        AdminActionRequiredOrderSearchForm searchForm = new AdminActionRequiredOrderSearchForm();

        AdminActionRequiredOrderSearchProjection projection = mock(AdminActionRequiredOrderSearchProjection.class);

        when(projection.getOrderId()).thenReturn(1L);
        when(projection.getHandlingStatusUpdatedAt()).thenReturn(null);

        Pageable pageable = PageRequest.of(0, 10);

        Page<AdminActionRequiredOrderSearchProjection> projectionPage = new PageImpl<>(
                List.of(projection),
                pageable,
                1);

        when(orderRepository.searchActionRequiredOrders(
                eq(null),
                eq(null),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(null),
                eq(List.of("NEEDS_ACTION", "IN_PROGRESS")),
                eq(null),
                eq("ALL"),
                eq(null),
                eq("OLDEST"),
                eq(pageable)))
                .thenReturn(projectionPage);

        Order order = new Order(10L, 1000);

        org.springframework.test.util.ReflectionTestUtils
                .setField(order, "id", 1L);

        when(orderRepository.findAllWithAssignedAdminByIdIn(List.of(1L)))
                .thenReturn(List.of(order));

        Page<AdminActionRequiredOrderDto> result = orderService.searchActionRequiredOrderDetails(
                searchForm,
                loginAdminAccountId,
                0,
                10);

        AdminActionRequiredOrderDto dto = result.getContent().get(0);

        assertSame(order, dto.order());
        assertEquals(null, dto.handlingStatusUpdatedAt());
        assertEquals(null, dto.elapsedDays());
    }

    @Test
    void getActionRequiredAgingSummaryReturnsRepositoryCounts() {

        ActionRequiredAgingSummaryProjection projection = mock(ActionRequiredAgingSummaryProjection.class);

        when(projection.getThreeDaysOrMoreCount())
                .thenReturn(5L);

        when(projection.getSevenDaysOrMoreCount())
                .thenReturn(2L);

        when(orderRepository.findActionRequiredAgingSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenReturn(projection);

        ActionRequiredAgingSummary result = orderService.getActionRequiredAgingSummary();

        assertEquals(5L, result.threeDaysOrMoreCount());
        assertEquals(2L, result.sevenDaysOrMoreCount());

        verify(orderRepository)
                .findActionRequiredAgingSummary(
                        any(LocalDateTime.class),
                        any(LocalDateTime.class));
    }

    @Test
    void changeHandlingStatusRejectsAssigningDisabledAdmin() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";
        Long assignedAdminId = 30L;

        Order order = new Order(10L, 1000);
        order.changeHandlingStatus(
                OrderHandlingStatus.NEEDS_ACTION);

        AdminAccount disabledAdmin = new AdminAccount();
        disabledAdmin.setUsername("disabledAdmin");
        disabledAdmin.setEnabled(false);

        org.springframework.test.util.ReflectionTestUtils
                .setField(
                        disabledAdmin,
                        "id",
                        assignedAdminId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        when(adminAccountService.findById(assignedAdminId))
                .thenReturn(disabledAdmin);

        assertThrows(
                IllegalArgumentException.class,
                () -> orderService.changeHandlingStatus(
                        orderId,
                        OrderHandlingStatus.NEEDS_ACTION,
                        assignedAdminId,
                        adminId,
                        adminUsername));

        assertNull(order.getAssignedAdminAccount());

        verifyNoInteractions(
                orderHandlingStatusHistoryService);
    }

    @Test
    void changeHandlingStatusAllowsKeepingDisabledExistingAssignee() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";
        Long assignedAdminId = 30L;

        Order order = new Order(10L, 1000);
        order.changeHandlingStatus(
                OrderHandlingStatus.NEEDS_ACTION);

        AdminAccount disabledAdmin = new AdminAccount();
        disabledAdmin.setUsername("disabledAdmin");
        disabledAdmin.setEnabled(false);

        org.springframework.test.util.ReflectionTestUtils
                .setField(
                        disabledAdmin,
                        "id",
                        assignedAdminId);

        order.changeAssignedAdminAccount(disabledAdmin);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        boolean changed = orderService.changeHandlingStatus(
                orderId,
                OrderHandlingStatus.IN_PROGRESS,
                assignedAdminId,
                adminId,
                adminUsername);

        assertEquals(true, changed);

        assertSame(
                disabledAdmin,
                order.getAssignedAdminAccount());

        assertEquals(
                OrderHandlingStatus.IN_PROGRESS,
                order.getHandlingStatus());

        verify(orderHandlingStatusHistoryService)
                .record(
                        eq(order),
                        eq(OrderHandlingStatus.NEEDS_ACTION),
                        eq(OrderHandlingStatus.IN_PROGRESS),
                        eq(adminId),
                        eq(adminUsername),
                        any(UUID.class));

        verifyNoInteractions(adminAccountService);
    }

    @Test
    void changeHandlingStatusRejectsNewAssignmentWhenResultingStatusIsResolved() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";
        Long assignedAdminId = 30L;

        Order order = new Order(10L, 1000);
        order.changeHandlingStatus(
                OrderHandlingStatus.IN_PROGRESS);

        AdminAccount assignedAdmin = new AdminAccount();
        assignedAdmin.setUsername("admin02");
        assignedAdmin.setEnabled(true);

        org.springframework.test.util.ReflectionTestUtils
                .setField(
                        assignedAdmin,
                        "id",
                        assignedAdminId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        assertThrows(
                IllegalArgumentException.class,
                () -> orderService.changeHandlingStatus(
                        orderId,
                        OrderHandlingStatus.RESOLVED,
                        assignedAdminId,
                        adminId,
                        adminUsername));

        assertNull(order.getAssignedAdminAccount());

        assertEquals(
                OrderHandlingStatus.IN_PROGRESS,
                order.getHandlingStatus());

        verifyNoInteractions(
                orderHandlingStatusHistoryService);

        verifyNoInteractions(
                adminAccountService);
    }

    @Test
    void changeHandlingStatusAllowsKeepingAssigneeWhenResultingStatusIsResolved() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";
        Long assignedAdminId = 30L;

        Order order = new Order(10L, 1000);
        order.changeHandlingStatus(
                OrderHandlingStatus.IN_PROGRESS);

        AdminAccount assignedAdmin = new AdminAccount();
        assignedAdmin.setUsername("admin02");
        assignedAdmin.setEnabled(true);

        org.springframework.test.util.ReflectionTestUtils
                .setField(
                        assignedAdmin,
                        "id",
                        assignedAdminId);

        order.changeAssignedAdminAccount(assignedAdmin);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        boolean changed = orderService.changeHandlingStatus(
                orderId,
                OrderHandlingStatus.RESOLVED,
                assignedAdminId,
                adminId,
                adminUsername);

        assertEquals(true, changed);

        assertSame(
                assignedAdmin,
                order.getAssignedAdminAccount());

        assertEquals(
                OrderHandlingStatus.RESOLVED,
                order.getHandlingStatus());

        verify(orderHandlingStatusHistoryService)
                .record(
                        eq(order),
                        eq(OrderHandlingStatus.IN_PROGRESS),
                        eq(OrderHandlingStatus.RESOLVED),
                        eq(adminId),
                        eq(adminUsername),
                        any(UUID.class));

        verifyNoInteractions(adminAccountService);
    }

    @Test
    void searchActionRequiredOrderDetailsUsesLoginAdminIdForMeFilter() {

        AdminActionRequiredOrderSearchForm searchForm = new AdminActionRequiredOrderSearchForm();

        searchForm.setAssigneeFilter(
                AdminOrderAssigneeFilter.ME);

        Long loginAdminAccountId = 20L;

        Pageable pageable = PageRequest.of(0, 10);

        Page<AdminActionRequiredOrderSearchProjection> projectionPage = new PageImpl<>(
                List.of(),
                pageable,
                0);

        when(orderRepository.searchActionRequiredOrders(
                eq(null),
                eq(null),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(null),
                eq(List.of("NEEDS_ACTION", "IN_PROGRESS")),
                eq(null),
                eq("ME"),
                eq(loginAdminAccountId),
                eq("OLDEST"),
                eq(pageable)))
                .thenReturn(projectionPage);

        Page<AdminActionRequiredOrderDto> result = orderService.searchActionRequiredOrderDetails(
                searchForm,
                loginAdminAccountId,
                0,
                10);

        assertEquals(0, result.getTotalElements());

        verify(orderRepository)
                .searchActionRequiredOrders(
                        eq(null),
                        eq(null),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        eq(null),
                        eq(List.of(
                                "NEEDS_ACTION",
                                "IN_PROGRESS")),
                        eq(null),
                        eq("ME"),
                        eq(loginAdminAccountId),
                        eq("OLDEST"),
                        eq(pageable));
    }

    @Test
    void searchActionRequiredOrderDetailsUsesSelectedAdminIdForSpecificFilter() {

        AdminActionRequiredOrderSearchForm searchForm = new AdminActionRequiredOrderSearchForm();

        searchForm.setAssigneeFilter(
                AdminOrderAssigneeFilter.SPECIFIC);

        Long selectedAdminAccountId = 30L;

        searchForm.setAssignedAdminAccountId(
                selectedAdminAccountId);

        Long loginAdminAccountId = 20L;

        Pageable pageable = PageRequest.of(0, 10);

        Page<AdminActionRequiredOrderSearchProjection> projectionPage = new PageImpl<>(
                List.of(),
                pageable,
                0);

        when(orderRepository.searchActionRequiredOrders(
                eq(null),
                eq(null),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(null),
                eq(List.of("NEEDS_ACTION", "IN_PROGRESS")),
                eq(null),
                eq("SPECIFIC"),
                eq(selectedAdminAccountId),
                eq("OLDEST"),
                eq(pageable)))
                .thenReturn(projectionPage);

        Page<AdminActionRequiredOrderDto> result = orderService.searchActionRequiredOrderDetails(
                searchForm,
                loginAdminAccountId,
                0,
                10);

        assertEquals(0, result.getTotalElements());

        verify(orderRepository)
                .searchActionRequiredOrders(
                        eq(null),
                        eq(null),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        eq(null),
                        eq(List.of(
                                "NEEDS_ACTION",
                                "IN_PROGRESS")),
                        eq(null),
                        eq("SPECIFIC"),
                        eq(selectedAdminAccountId),
                        eq("OLDEST"),
                        eq(pageable));
    }

    @Test
    void changeHandlingStatusUsesSameChangeEventIdForStatusAndAssigneeHistories() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";
        Long assignedAdminId = 30L;

        Order order = new Order(10L, 1000);

        AdminAccount assignedAdmin = new AdminAccount();
        assignedAdmin.setUsername("admin02");
        assignedAdmin.setEnabled(true);

        org.springframework.test.util.ReflectionTestUtils
                .setField(
                        assignedAdmin,
                        "id",
                        assignedAdminId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        when(adminAccountService.findById(assignedAdminId))
                .thenReturn(assignedAdmin);

        boolean changed = orderService.changeHandlingStatus(
                orderId,
                OrderHandlingStatus.NEEDS_ACTION,
                assignedAdminId,
                adminId,
                adminUsername);

        assertEquals(true, changed);

        ArgumentCaptor<UUID> handlingEventIdCaptor = ArgumentCaptor.forClass(UUID.class);

        verify(orderHandlingStatusHistoryService)
                .record(
                        eq(order),
                        eq(OrderHandlingStatus.NONE),
                        eq(OrderHandlingStatus.NEEDS_ACTION),
                        eq(adminId),
                        eq(adminUsername),
                        handlingEventIdCaptor.capture());

        ArgumentCaptor<UUID> assigneeEventIdCaptor = ArgumentCaptor.forClass(UUID.class);

        verify(orderAssigneeHistoryService)
                .record(
                        eq(order),
                        eq(null),
                        eq(null),
                        eq(assignedAdminId),
                        eq("admin02"),
                        eq(adminId),
                        eq(adminUsername),
                        assigneeEventIdCaptor.capture());

        assertEquals(
                handlingEventIdCaptor.getValue(),
                assigneeEventIdCaptor.getValue());

    }

}
