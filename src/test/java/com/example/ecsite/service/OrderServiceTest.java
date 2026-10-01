package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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
import com.example.ecsite.dto.AdminAssigneeActionRequiredSummary;
import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.entity.Category;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderCharge;
import com.example.ecsite.entity.OrderChargeType;
import com.example.ecsite.entity.OrderContentChangeCharge;
import com.example.ecsite.entity.OrderContentChangeHistory;
import com.example.ecsite.entity.OrderContentChangeHistoryActorType;
import com.example.ecsite.entity.OrderContentChangeItem;
import com.example.ecsite.entity.OrderContentChangeSource;
import com.example.ecsite.entity.OrderContentChangeType;
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.entity.OrderShippingAddressHistoryActorType;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.TaxCategory;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.form.ActionRequiredOrderSort;
import com.example.ecsite.form.AdminActionRequiredOrderSearchForm;
import com.example.ecsite.form.AdminOrderAssigneeFilter;
import com.example.ecsite.form.AdminOrderSearchForm;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.form.OrderItemChangeForm;
import com.example.ecsite.form.OrderShippingAddressForm;
import com.example.ecsite.repository.OrderContentChangeHistoryRepository;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.projection.ActionRequiredAgingSummaryProjection;
import com.example.ecsite.repository.projection.AdminActionRequiredOrderSearchProjection;
import com.example.ecsite.repository.projection.AdminAssigneeActionRequiredCountProjection;
import com.example.ecsite.service.pricing.ChargeTaxSnapshot;
import com.example.ecsite.service.pricing.OrderAmount;
import com.example.ecsite.service.pricing.OrderAmountCalculator;
import com.example.ecsite.service.pricing.OrderChargeAmount;

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
    private OrderShippingAddressHistoryService orderShippingAddressHistoryService;

    @Mock
    private AdminAccountService adminAccountService;

    @Mock
    private OrderAssigneeHistoryService orderAssigneeHistoryService;

    @Mock
    private TaxCategoryService taxCategoryService;

    @Mock
    private OrderAmountCalculator orderAmountCalculator;

    @Mock
    private OrderContentChangeHistoryRepository orderContentChangeHistoryRepository;

    private OrderService orderService;

    private OrderDeadlineCalculator orderDeadlineCalculator;

    private Clock clock;

    private static final List<OrderHandlingStatus> ALL_HANDLING_STATUSES = List.of(OrderHandlingStatus.values());

    @BeforeEach
    void setUp() {

        clock = Clock.fixed(
                LocalDateTime.of(2026, 9, 28, 10, 0)
                        .atZone(ZoneId.of("Asia/Tokyo"))
                        .toInstant(),
                ZoneId.of("Asia/Tokyo"));

        orderDeadlineCalculator = new OrderDeadlineCalculator(LocalTime.of(14, 0));

        orderService = createOrderService();
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

        TaxCategory shippingTaxCategory = createTaxCategory(
                300L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"));

        when(taxCategoryService.findStandardTaxCategory())
                .thenReturn(shippingTaxCategory);

        OrderChargeAmount shippingCharge = new OrderChargeAmount(
                OrderChargeType.SHIPPING,
                "送料・梱包料",
                550,
                300L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"),
                10);

        OrderAmount orderAmount = new OrderAmount(
                2000,
                List.of(shippingCharge),
                550,
                231,
                2550);

        when(orderAmountCalculator.calculate(
                any(),
                same(shippingTaxCategory)))
                .thenReturn(orderAmount);

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

        assertEquals(2000, order.getItemSubtotal());
        assertEquals(550, order.getChargeTotal());
        assertEquals(231, order.getTaxAmount());
        assertEquals(2550, order.getTotalAmount());
        assertEquals(1, order.getItems().size());
        assertEquals(100L, order.getItems().get(0).getCategoryId());
        assertEquals("テストカテゴリ", order.getItems().get(0).getCategoryName());
        assertEquals(LocalDateTime.of(2026, 9, 28, 10, 0), order.getOrderedAt());
        assertEquals(LocalDateTime.of(2026, 9, 28, 14, 0), order.getChangeDeadlineAt());
        assertEquals(200L, order.getItems().get(0).getTaxCategoryId());
        assertEquals("STANDARD", order.getItems().get(0).getTaxCategoryCode());
        assertEquals("標準税率", order.getItems().get(0).getTaxCategoryName());
        assertEquals(0, new BigDecimal("10.00").compareTo(order.getItems().get(0).getTaxRate()));
        assertEquals(1, order.getCharges().size());
        assertEquals(OrderChargeType.SHIPPING, order.getCharges().get(0).getChargeType());
        assertEquals("送料・梱包料", order.getCharges().get(0).getName());
        assertEquals(550, order.getCharges().get(0).getAmount());
        assertEquals(300L, order.getCharges().get(0).getTaxCategoryId());
        assertEquals("STANDARD", order.getCharges().get(0).getTaxCategoryCode());
        assertEquals("標準税率", order.getCharges().get(0).getTaxCategoryName());
        assertEquals(0, new BigDecimal("10.00").compareTo(order.getCharges().get(0).getTaxRate()));

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

        verify(taxCategoryService)
                .findStandardTaxCategory();

        verify(orderAmountCalculator)
                .calculate(
                        any(),
                        same(shippingTaxCategory));
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

    @Test
    void findOrdersByUserIdUsesSpecifiedPagingConditions() {

        Long userId = 10L;
        int page = 1;
        int size = 2;

        Pageable expectedPageable = PageRequest.of(page, size);

        Page<Order> expectedPage = new PageImpl<>(
                List.of(
                        createOrder(userId, 1000),
                        createOrder(userId, 2000)),
                expectedPageable,
                5);

        when(orderRepository
                .findByUserIdOrderByOrderedAtDesc(
                        userId,
                        expectedPageable))
                .thenReturn(expectedPage);

        OrderService orderService = createOrderService();

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

        OrderService orderService = createOrderService();

        orderService.cancelOrder(
                orderId,
                adminId,
                adminUsername,
                internalNote);

        verify(order).cancel(LocalDateTime.of(2026, 9, 28, 10, 0));

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

        OrderService orderService = createOrderService();

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

        Order order = createOrder(10L, 1000);
        order.markAsPaid();

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        OrderService orderService = createOrderService();

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
        TaxCategory taxCategory = mock(TaxCategory.class);
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

        when(product.getTaxCategory())
                .thenReturn(taxCategory);

        when(taxCategory.getId())
                .thenReturn(200L);

        when(taxCategory.getCode())
                .thenReturn("STANDARD");

        when(taxCategory.getName())
                .thenReturn("標準税率");

        when(taxCategory.getTaxRate())
                .thenReturn(new BigDecimal("10.00"));

        Long orderId = 100L;

        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> {
                    Order order = invocation.getArgument(0);

                    org.springframework.test.util.ReflectionTestUtils
                            .setField(order, "id", orderId);

                    return order;
                });

        mockOrderAmountCalculation(
                3000,
                550,
                322,
                3550);

        OrderService orderService = createOrderService();

        Order result = orderService.createOrder(
                userId,
                username,
                cart,
                checkoutForm);

        assertEquals(3550, result.getTotalAmount());
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

        OrderService orderService = createOrderService();

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

    @Test
    void createOrderKeepsZeroAmountShippingChargeWhenShippingIsFree() {

        Long userId = 10L;
        String username = "testuser";

        Product product = createProduct(
                1L,
                "送料無料商品",
                5000,
                5);

        Cart cart = new Cart();
        cart.addItem(new CartItem(
                1L,
                "送料無料商品",
                5000,
                1));

        when(productService.findByIdForUpdate(1L))
                .thenReturn(product);

        mockOrderAmountCalculation(
                5000,
                0,
                454,
                5000);

        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> {
                    Order order = invocation.getArgument(0);

                    org.springframework.test.util.ReflectionTestUtils
                            .setField(order, "id", 100L);

                    return order;
                });

        Order order = orderService.createOrder(
                userId,
                username,
                cart,
                createCheckoutForm());

        assertEquals(5000, order.getItemSubtotal());
        assertEquals(0, order.getChargeTotal());
        assertEquals(454, order.getTaxAmount());
        assertEquals(5000, order.getTotalAmount());

        assertEquals(1, order.getCharges().size());

        assertEquals(
                OrderChargeType.SHIPPING,
                order.getCharges().get(0).getChargeType());

        assertEquals(
                "送料・梱包料",
                order.getCharges().get(0).getName());

        assertEquals(
                0,
                order.getCharges().get(0).getAmount());

        assertEquals(
                "STANDARD",
                order.getCharges().get(0).getTaxCategoryCode());

        verify(orderRepository).save(order);
    }

    @Test
    void calculateOrderAmountReturnsPricingResult() {

        Product product = createProduct(
                1L,
                "テスト商品",
                2000,
                5);

        Cart cart = new Cart();

        cart.addItem(new CartItem(
                1L,
                "テスト商品",
                2000,
                2));

        when(productService.findById(1L))
                .thenReturn(product);

        TaxCategory shippingTaxCategory = createTaxCategory(
                300L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"));

        when(taxCategoryService.findStandardTaxCategory())
                .thenReturn(shippingTaxCategory);

        OrderAmount expected = new OrderAmount(
                4000,
                List.of(),
                550,
                413,
                4550);

        when(orderAmountCalculator.calculate(
                any(),
                same(shippingTaxCategory)))
                .thenReturn(expected);

        OrderAmount result = orderService.calculateOrderAmount(cart);

        assertSame(expected, result);

        verify(productService)
                .findById(1L);

        verify(taxCategoryService)
                .findStandardTaxCategory();

        verify(orderAmountCalculator)
                .calculate(
                        any(),
                        same(shippingTaxCategory));
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

        OrderService orderService = createOrderService();

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

        OrderService orderService = createOrderService();

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

        OrderService orderService = createOrderService();

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

        OrderService orderService = createOrderService();

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

        OrderService orderService = createOrderService();

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

        when(order.canCancel())
                .thenReturn(true);

        when(order.isWithinModificationPeriod(any(LocalDateTime.class)))
                .thenReturn(true);

        OrderService orderService = createOrderService();

        orderService.cancelOrderForUser(
                orderId,
                userId,
                username);

        verify(order).cancel(LocalDateTime.of(2026, 9, 28, 10, 0));

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
    void cancelOrderForUserRejectsOrderAtOrAfterChangeDeadline() {

        Long orderId = 1L;
        Long userId = 10L;
        String username = "testuser";

        Order order = mock(Order.class);

        when(orderRepository
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId))
                .thenReturn(Optional.of(order));

        when(order.canCancel())
                .thenReturn(true);

        when(order.isWithinModificationPeriod(
                any(LocalDateTime.class)))
                .thenReturn(false);

        InvalidOrderStatusException exception = assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.cancelOrderForUser(
                        orderId,
                        userId,
                        username));

        assertEquals(
                "この注文の変更受付は終了しています。",
                exception.getMessage());

        verify(order, never()).cancel(any(LocalDateTime.class));

        verifyNoInteractions(inventoryService);
    }

    @Test
    void findOrderByIdAndUserIdReturnsOwnedOrder() {

        Long orderId = 1L;
        Long userId = 10L;

        Order expectedOrder = createOrder(userId, 1000);

        when(orderRepository
                .findByIdAndUserIdWithItems(
                        orderId,
                        userId))
                .thenReturn(Optional.of(expectedOrder));

        when(orderRepository.findByIdAndUserIdWithCharges(
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

        OrderService orderService = createOrderService();

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

        mockOrderAmountCalculation(
                2000,
                550,
                231,
                2550);

        OrderService orderService = createOrderService();

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

        Order firstOrder = createOrder(10L, 1000);
        Order secondOrder = createOrder(10L, 2000);

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

        Order order = createOrder(10L, 1000);

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

        Order order = createOrder(10L, 1000);
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

        Order order = createOrder(10L, 1000);

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

        Order order = createOrder(10L, 1000);
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

        Order order = createOrder(10L, 1000);

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

        Order order = createOrder(10L, 1000);
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

        Order order = createOrder(10L, 1000);
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

        Order order = createOrder(10L, 1000);

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

        Order order = createOrder(10L, 1000);

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
                any(LocalDateTime.class),
                eq("ALL"),
                isNull()))
                .thenReturn(projection);

        ActionRequiredAgingSummary result = orderService.getActionRequiredAgingSummary();

        assertEquals(5L, result.threeDaysOrMoreCount());
        assertEquals(2L, result.sevenDaysOrMoreCount());

        verify(orderRepository)
                .findActionRequiredAgingSummary(
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        eq("ALL"),
                        isNull());
    }

    @Test
    void getMyAssignedActionRequiredAgingSummaryReturnsRepositoryCounts() {

        Long loginAdminAccountId = 20L;

        ActionRequiredAgingSummaryProjection projection = mock(ActionRequiredAgingSummaryProjection.class);

        when(projection.getThreeDaysOrMoreCount())
                .thenReturn(5L);

        when(projection.getSevenDaysOrMoreCount())
                .thenReturn(2L);

        when(orderRepository.findActionRequiredAgingSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("ME"),
                eq(loginAdminAccountId)))
                .thenReturn(projection);

        ActionRequiredAgingSummary result = orderService.getMyAssignedActionRequiredAgingSummary(
                loginAdminAccountId);

        assertEquals(
                5L,
                result.threeDaysOrMoreCount());

        assertEquals(
                2L,
                result.sevenDaysOrMoreCount());

        verify(orderRepository).findActionRequiredAgingSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("ME"),
                eq(loginAdminAccountId));
    }

    @Test
    void changeHandlingStatusRejectsAssigningDisabledAdmin() {

        Long orderId = 1L;
        Long adminId = 20L;
        String adminUsername = "admin";
        Long assignedAdminId = 30L;

        Order order = createOrder(10L, 1000);
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

        Order order = createOrder(10L, 1000);
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

        Order order = createOrder(10L, 1000);
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

        Order order = createOrder(10L, 1000);
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

        Order order = createOrder(10L, 1000);

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

    @Test
    void searchMyAssignedOrderDetailsForcesLoggedInAdmin() {

        Long loginAdminAccountId = 20L;

        AdminActionRequiredOrderSearchForm searchForm = new AdminActionRequiredOrderSearchForm();

        searchForm.setAssigneeFilter(
                AdminOrderAssigneeFilter.SPECIFIC);

        searchForm.setAssignedAdminAccountId(999L);

        Pageable pageable = PageRequest.of(0, 10);

        Page<AdminActionRequiredOrderSearchProjection> projectionPage = new PageImpl<>(
                List.of(),
                pageable,
                0);

        when(orderRepository.searchActionRequiredOrders(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq(AdminOrderAssigneeFilter.ME.name()),
                eq(loginAdminAccountId),
                eq(ActionRequiredOrderSort.OLDEST.name()),
                eq(pageable)))
                .thenReturn(projectionPage);

        Page<AdminActionRequiredOrderDto> result = orderService.searchMyAssignedOrderDetails(
                searchForm,
                loginAdminAccountId,
                0,
                10);

        assertEquals(0, result.getTotalElements());

        verify(orderRepository).searchActionRequiredOrders(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq(AdminOrderAssigneeFilter.ME.name()),
                eq(loginAdminAccountId),
                eq(ActionRequiredOrderSort.OLDEST.name()),
                eq(pageable));
    }

    @Test
    void searchUnassignedOrderDetailsForcesUnassignedFilter() {

        AdminActionRequiredOrderSearchForm searchForm = new AdminActionRequiredOrderSearchForm();

        searchForm.setAssigneeFilter(
                AdminOrderAssigneeFilter.SPECIFIC);

        searchForm.setAssignedAdminAccountId(999L);

        Pageable pageable = PageRequest.of(0, 10);

        Page<AdminActionRequiredOrderSearchProjection> projectionPage = new PageImpl<>(
                List.of(),
                pageable,
                0);

        when(orderRepository.searchActionRequiredOrders(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq(AdminOrderAssigneeFilter.UNASSIGNED.name()),
                isNull(),
                eq(ActionRequiredOrderSort.OLDEST.name()),
                eq(pageable)))
                .thenReturn(projectionPage);

        Page<AdminActionRequiredOrderDto> result = orderService.searchUnassignedOrderDetails(
                searchForm,
                0,
                10);

        assertEquals(
                0,
                result.getTotalElements());

        verify(orderRepository).searchActionRequiredOrders(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq(AdminOrderAssigneeFilter.UNASSIGNED.name()),
                isNull(),
                eq(ActionRequiredOrderSort.OLDEST.name()),
                eq(pageable));
    }

    @Test
    void countMyAssignedActionRequiredOrdersCountsNeedsActionAndInProgress() {

        Long loginAdminAccountId = 20L;

        when(orderRepository
                .countByAssignedAdminAccount_IdAndHandlingStatusIn(
                        loginAdminAccountId,
                        List.of(
                                OrderHandlingStatus.NEEDS_ACTION,
                                OrderHandlingStatus.IN_PROGRESS)))
                .thenReturn(3L);

        long result = orderService.countMyAssignedActionRequiredOrders(
                loginAdminAccountId);

        assertEquals(3L, result);

        verify(orderRepository)
                .countByAssignedAdminAccount_IdAndHandlingStatusIn(
                        loginAdminAccountId,
                        List.of(
                                OrderHandlingStatus.NEEDS_ACTION,
                                OrderHandlingStatus.IN_PROGRESS));
    }

    @Test
    void countUnassignedActionRequiredOrdersCountsNeedsActionAndInProgress() {

        when(orderRepository
                .countByAssignedAdminAccountIsNullAndHandlingStatusIn(
                        List.of(
                                OrderHandlingStatus.NEEDS_ACTION,
                                OrderHandlingStatus.IN_PROGRESS)))
                .thenReturn(4L);

        long result = orderService.countUnassignedActionRequiredOrders();

        assertEquals(
                4L,
                result);

        verify(orderRepository)
                .countByAssignedAdminAccountIsNullAndHandlingStatusIn(
                        List.of(
                                OrderHandlingStatus.NEEDS_ACTION,
                                OrderHandlingStatus.IN_PROGRESS));
    }

    @Test
    void getUnassignedActionRequiredAgingSummaryReturnsRepositoryCounts() {

        ActionRequiredAgingSummaryProjection projection = mock(ActionRequiredAgingSummaryProjection.class);

        when(projection.getThreeDaysOrMoreCount())
                .thenReturn(5L);

        when(projection.getSevenDaysOrMoreCount())
                .thenReturn(2L);

        when(orderRepository.findActionRequiredAgingSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("UNASSIGNED"),
                isNull()))
                .thenReturn(projection);

        ActionRequiredAgingSummary result = orderService.getUnassignedActionRequiredAgingSummary();

        assertEquals(
                5L,
                result.threeDaysOrMoreCount());

        assertEquals(
                2L,
                result.sevenDaysOrMoreCount());

        verify(orderRepository).findActionRequiredAgingSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("UNASSIGNED"),
                isNull());
    }

    @Test
    void getActionRequiredOrderCountsByAssigneeMapsRepositoryProjectionsWithAgingCounts() {

        AdminAssigneeActionRequiredCountProjection first = mock(
                AdminAssigneeActionRequiredCountProjection.class);

        when(first.getAdminAccountId())
                .thenReturn(10L);

        when(first.getUsername())
                .thenReturn("admin01");

        when(first.getEnabled())
                .thenReturn(true);

        when(first.getOrderCount())
                .thenReturn(5L);

        when(first.getThreeDaysOrMoreCount())
                .thenReturn(3L);

        when(first.getSevenDaysOrMoreCount())
                .thenReturn(1L);

        AdminAssigneeActionRequiredCountProjection second = mock(
                AdminAssigneeActionRequiredCountProjection.class);

        when(second.getAdminAccountId())
                .thenReturn(20L);

        when(second.getUsername())
                .thenReturn("admin02");

        when(second.getEnabled())
                .thenReturn(false);

        when(second.getOrderCount())
                .thenReturn(2L);

        when(second.getThreeDaysOrMoreCount())
                .thenReturn(1L);

        when(second.getSevenDaysOrMoreCount())
                .thenReturn(0L);

        when(orderRepository.findActionRequiredOrderCountsByAssignee(
                any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));

        List<AdminAssigneeActionRequiredSummary> result = orderService
                .getActionRequiredOrderCountsByAssignee();

        assertEquals(2, result.size());

        assertEquals(
                new AdminAssigneeActionRequiredSummary(
                        10L,
                        "admin01",
                        true,
                        5L,
                        3L,
                        1L),
                result.get(0));

        assertEquals(
                new AdminAssigneeActionRequiredSummary(
                        20L,
                        "admin02",
                        false,
                        2L,
                        1L,
                        0L),
                result.get(1));

        verify(orderRepository)
                .findActionRequiredOrderCountsByAssignee(
                        any(LocalDateTime.class),
                        any(LocalDateTime.class));
    }

    @Test
    void changeShippingAddressForUserUpdatesOrderWithinDeadline() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrder(
                userId,
                1000);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        order.setShippingAddress(
                "山田 太郎",
                "100-0001",
                "東京都",
                "千代田区",
                "千代田1-1",
                "090-1111-2222");

        OrderShippingAddressForm form = createOrderShippingAddressForm();

        boolean changed = orderService.changeShippingAddressForUser(
                orderId,
                userId,
                "testuser",
                form);

        assertEquals(
                "佐藤 花子",
                order.getShippingName());

        assertEquals(
                "150-0001",
                order.getShippingPostalCode());

        assertEquals(
                "東京都",
                order.getShippingPrefecture());

        assertEquals(
                "渋谷区",
                order.getShippingCity());

        assertEquals(
                "神宮前1-2-3",
                order.getShippingAddressLine());

        assertEquals(
                "080-1234-5678",
                order.getShippingPhone());

        assertTrue(changed);

        verify(orderShippingAddressHistoryService)
                .record(
                        order,
                        OrderShippingAddressHistoryActorType.USER,
                        userId,
                        "testuser",
                        "山田 太郎",
                        "100-0001",
                        "東京都",
                        "千代田区",
                        "千代田1-1",
                        "090-1111-2222",
                        "佐藤 花子",
                        "150-0001",
                        "東京都",
                        "渋谷区",
                        "神宮前1-2-3",
                        "080-1234-5678");
    }

    @Test
    void changeShippingAddressForUserAllowsPaidOrderWithinDeadline() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrder(
                userId,
                1000);

        order.markAsPaid();

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        OrderShippingAddressForm form = createOrderShippingAddressForm();

        orderService.changeShippingAddressForUser(
                orderId,
                userId,
                "testuser",
                form);

        assertEquals(
                "佐藤 花子",
                order.getShippingName());
    }

    @Test
    void changeShippingAddressForUserRejectsShippedOrder() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrder(
                userId,
                1000);

        order.markAsPaid();
        order.markAsShipped();

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        InvalidOrderStatusException exception = assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.changeShippingAddressForUser(
                        orderId,
                        userId,
                        "testuser",
                        createOrderShippingAddressForm()));

        assertEquals(
                "現在の注文状態では配送先を変更できません。",
                exception.getMessage());

        verifyNoInteractions(orderShippingAddressHistoryService);
    }

    @Test
    void changeShippingAddressForUserRejectsCancelledOrder() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrder(
                userId,
                1000);

        order.cancel(LocalDateTime.of(2026, 9, 28, 12, 0));

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        InvalidOrderStatusException exception = assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.changeShippingAddressForUser(
                        orderId,
                        userId,
                        "testuser",
                        createOrderShippingAddressForm()));

        assertEquals(
                "現在の注文状態では配送先を変更できません。",
                exception.getMessage());

        verifyNoInteractions(orderShippingAddressHistoryService);
    }

    @Test
    void changeShippingAddressForUserDoesNotRecordHistoryWhenAddressIsUnchanged() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrder(
                userId,
                1000);

        order.setShippingAddress(
                "佐藤 花子",
                "150-0001",
                "東京都",
                "渋谷区",
                "神宮前1-2-3",
                "080-1234-5678");

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        boolean changed = orderService.changeShippingAddressForUser(
                orderId,
                userId,
                "testuser",
                createOrderShippingAddressForm());

        assertFalse(changed);

        verifyNoInteractions(
                orderShippingAddressHistoryService);

        assertEquals(
                "佐藤 花子",
                order.getShippingName());
    }

    @Test
    void changeShippingAddressForUserAllowsOneSecondBeforeDeadline() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrder(
                userId,
                1000);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        clock = Clock.fixed(
                LocalDateTime.of(
                        2026, 9, 28, 13, 59, 59)
                        .atZone(ZoneId.of("Asia/Tokyo"))
                        .toInstant(),
                ZoneId.of("Asia/Tokyo"));

        orderService = createOrderService();

        orderService.changeShippingAddressForUser(
                orderId,
                userId,
                "testuser",
                createOrderShippingAddressForm());

        assertEquals(
                "佐藤 花子",
                order.getShippingName());
    }

    @Test
    void changeShippingAddressForUserRejectsExactlyAtDeadline() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrder(
                userId,
                1000);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        clock = Clock.fixed(
                LocalDateTime.of(
                        2026, 9, 28, 14, 0, 0)
                        .atZone(ZoneId.of("Asia/Tokyo"))
                        .toInstant(),
                ZoneId.of("Asia/Tokyo"));

        orderService = createOrderService();

        InvalidOrderStatusException exception = assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.changeShippingAddressForUser(
                        orderId,
                        userId,
                        "testuser",
                        createOrderShippingAddressForm()));

        assertEquals(
                "この注文の変更受付は終了しています。",
                exception.getMessage());

        verifyNoInteractions(orderShippingAddressHistoryService);
    }

    @Test
    void changeShippingAddressForUserRejectsOneSecondAfterDeadline() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrder(
                userId,
                1000);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        clock = Clock.fixed(
                LocalDateTime.of(
                        2026, 9, 28, 14, 0, 1)
                        .atZone(ZoneId.of("Asia/Tokyo"))
                        .toInstant(),
                ZoneId.of("Asia/Tokyo"));

        orderService = createOrderService();

        InvalidOrderStatusException exception = assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.changeShippingAddressForUser(
                        orderId,
                        userId,
                        "testuser",
                        createOrderShippingAddressForm()));

        assertEquals(
                "この注文の変更受付は終了しています。",
                exception.getMessage());

        verifyNoInteractions(orderShippingAddressHistoryService);
    }

    @Test
    void changeShippingAddressForUserRejectsOrderOwnedByAnotherUser() {

        Long orderId = 1L;
        Long userId = 10L;

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> orderService.changeShippingAddressForUser(
                        orderId,
                        userId,
                        "testuser",
                        createOrderShippingAddressForm()));

        verifyNoInteractions(orderShippingAddressHistoryService);
    }

    @Test
    void cancelOrderForUserRejectsOrderOwnedByAnotherUser() {

        Long orderId = 1L;
        Long userId = 10L;
        String username = "testuser";

        when(orderRepository
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> orderService.cancelOrderForUser(
                        orderId,
                        userId,
                        username));

        verifyNoInteractions(inventoryService);
        verifyNoInteractions(orderStatusHistoryService);
    }

    @Test
    void cancelOrderForUserRejectsAlreadyCancelledOrder() {

        Long orderId = 1L;
        Long userId = 10L;
        String username = "testuser";

        Order order = mock(Order.class);

        when(orderRepository
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId))
                .thenReturn(Optional.of(order));

        when(order.canCancel())
                .thenReturn(false);

        InvalidOrderStatusException exception = assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.cancelOrderForUser(
                        orderId,
                        userId,
                        username));

        assertEquals(
                "注文受付中の注文だけをキャンセルできます。",
                exception.getMessage());

        verify(order, never())
                .cancel(any(LocalDateTime.class));

        verifyNoInteractions(inventoryService);
        verifyNoInteractions(orderStatusHistoryService);
    }

    @Test
    void cancelOrderForUserRestoresStockForAllOrderItems() {

        Long orderId = 1L;
        Long userId = 10L;
        String username = "testuser";

        Order order = mock(Order.class);

        OrderItem firstItem = mock(OrderItem.class);
        OrderItem secondItem = mock(OrderItem.class);

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

        when(order.canCancel())
                .thenReturn(true);

        when(order.isWithinModificationPeriod(
                any(LocalDateTime.class)))
                .thenReturn(true);

        when(order.getItems())
                .thenReturn(List.of(
                        firstItem,
                        secondItem));

        when(firstItem.getProductId())
                .thenReturn(20L);

        when(firstItem.getQuantity())
                .thenReturn(2);

        when(secondItem.getProductId())
                .thenReturn(30L);

        when(secondItem.getQuantity())
                .thenReturn(4);

        orderService.cancelOrderForUser(
                orderId,
                userId,
                username);

        verify(inventoryService, times(1))
                .restoreForOrderCancellation(
                        20L,
                        2,
                        orderId);

        verify(inventoryService, times(1))
                .restoreForOrderCancellation(
                        30L,
                        4,
                        orderId);

        verifyNoMoreInteractions(inventoryService);
    }

    @Test
    void changeItemsForUserDecreasesQuantityAndRestoresStock() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrderForItemChange(
                orderId,
                userId);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        OrderChargeAmount shippingCharge = new OrderChargeAmount(
                OrderChargeType.SHIPPING,
                "送料・梱包料",
                550,
                300L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"),
                10);

        OrderAmount changedAmount = new OrderAmount(
                3500,
                List.of(shippingCharge),
                550,
                368,
                4050);

        when(orderAmountCalculator.calculate(
                any(),
                any(ChargeTaxSnapshot.class)))
                .thenReturn(changedAmount);

        OrderItemChangeForm form = createOrderItemChangeForm(
                0,
                1,
                1);

        boolean changed = orderService.changeItemsForUser(
                orderId,
                userId,
                "testuser",
                form);

        assertTrue(changed);

        assertEquals(
                1,
                order.getItems().get(0).getQuantity());

        assertEquals(
                1,
                order.getItems().get(1).getQuantity());

        assertEquals(
                3500,
                order.getItemSubtotal());

        assertEquals(
                550,
                order.getChargeTotal());

        assertEquals(
                4050,
                order.getTotalAmount());

        assertEquals(
                1,
                order.getContentRevision());

        verify(inventoryService)
                .restoreForOrderItemChange(
                        10L,
                        1,
                        orderId);

        ArgumentCaptor<OrderContentChangeHistory> historyCaptor = ArgumentCaptor
                .forClass(OrderContentChangeHistory.class);

        verify(orderContentChangeHistoryRepository)
                .save(historyCaptor.capture());

        OrderContentChangeHistory history = historyCaptor.getValue();

        assertEquals(orderId, history.getOrder().getId());

        assertEquals(
                OrderContentChangeSource.CUSTOMER,
                history.getChangeSource());

        assertEquals(
                OrderContentChangeHistoryActorType.USER,
                history.getChangedByType());

        assertEquals(
                userId,
                history.getChangedByAccountId());

        assertEquals(
                "testuser",
                history.getChangedByUsername());

        assertEquals(
                5500,
                history.getOldItemSubtotal());

        assertEquals(
                3500,
                history.getNewItemSubtotal());

        assertEquals(
                0,
                history.getOldChargeTotal());

        assertEquals(
                550,
                history.getNewChargeTotal());

        assertEquals(
                500,
                history.getOldTaxAmount());

        assertEquals(
                368,
                history.getNewTaxAmount());

        assertEquals(
                5500,
                history.getOldTotalAmount());

        assertEquals(
                4050,
                history.getNewTotalAmount());

        assertEquals(
                1,
                history.getItems().size());

        OrderContentChangeItem changedItem = history.getItems().get(0);

        assertEquals(
                OrderContentChangeType.UPDATED,
                changedItem.getChangeType());

        assertEquals(
                1001L,
                changedItem.getOrderItemId());

        assertEquals(
                10L,
                changedItem.getProductId());

        assertEquals(
                "商品A",
                changedItem.getProductName());

        assertEquals(
                2,
                changedItem.getOldQuantity());

        assertEquals(
                1,
                changedItem.getNewQuantity());

        assertEquals(
                4000,
                changedItem.getOldSubtotal());

        assertEquals(
                2000,
                changedItem.getNewSubtotal());

        assertEquals(
                1,
                history.getCharges().size());

        OrderContentChangeCharge changedCharge = history.getCharges().get(0);

        assertEquals(
                OrderContentChangeType.UPDATED,
                changedCharge.getChangeType());

        assertEquals(
                OrderChargeType.SHIPPING,
                changedCharge.getChargeType());

        assertEquals(
                0,
                changedCharge.getOldAmount());

        assertEquals(
                550,
                changedCharge.getNewAmount());
    }

    @Test
    void changeItemsForUserRemovesItemAndRestoresAllItemStock() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrderForItemChange(
                orderId,
                userId);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        OrderChargeAmount shippingCharge = new OrderChargeAmount(
                OrderChargeType.SHIPPING,
                "送料・梱包料",
                550,
                300L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"),
                10);

        OrderAmount changedAmount = new OrderAmount(
                1500,
                List.of(shippingCharge),
                550,
                186,
                2050);

        when(orderAmountCalculator.calculate(
                any(),
                any(ChargeTaxSnapshot.class)))
                .thenReturn(changedAmount);

        OrderItemChangeForm form = createOrderItemChangeForm(
                0,
                0,
                1);

        boolean changed = orderService.changeItemsForUser(
                orderId,
                userId,
                "testuser",
                form);

        assertTrue(changed);

        assertEquals(
                1,
                order.getItems().size());

        assertEquals(
                1002L,
                order.getItems().get(0).getId());

        assertEquals(
                20L,
                order.getItems().get(0).getProductId());

        assertEquals(
                1,
                order.getItems().get(0).getQuantity());

        assertEquals(
                1500,
                order.getItemSubtotal());

        assertEquals(
                550,
                order.getChargeTotal());

        assertEquals(
                2050,
                order.getTotalAmount());

        assertEquals(
                1,
                order.getContentRevision());

        verify(inventoryService)
                .restoreForOrderItemChange(
                        10L,
                        2,
                        orderId);

        verify(inventoryService, never())
                .restoreForOrderItemChange(
                        eq(20L),
                        anyInt(),
                        eq(orderId));

        ArgumentCaptor<OrderContentChangeHistory> historyCaptor = ArgumentCaptor
                .forClass(OrderContentChangeHistory.class);

        verify(orderContentChangeHistoryRepository)
                .save(historyCaptor.capture());

        OrderContentChangeHistory history = historyCaptor.getValue();

        assertEquals(1, history.getItems().size());

        OrderContentChangeItem removedItem = history.getItems().get(0);

        assertEquals(
                OrderContentChangeType.REMOVED,
                removedItem.getChangeType());

        assertEquals(
                1001L,
                removedItem.getOrderItemId());

        assertEquals(
                10L,
                removedItem.getProductId());

        assertEquals(
                "商品A",
                removedItem.getProductName());

        assertEquals(
                2,
                removedItem.getOldQuantity());

        assertNull(
                removedItem.getNewQuantity());

        assertEquals(
                4000,
                removedItem.getOldSubtotal());

        assertNull(
                removedItem.getNewSubtotal());

        assertEquals(
                5500,
                history.getOldItemSubtotal());

        assertEquals(
                1500,
                history.getNewItemSubtotal());

        assertEquals(
                0,
                history.getOldChargeTotal());

        assertEquals(
                550,
                history.getNewChargeTotal());

        assertEquals(
                500,
                history.getOldTaxAmount());

        assertEquals(
                186,
                history.getNewTaxAmount());

        assertEquals(
                5500,
                history.getOldTotalAmount());

        assertEquals(
                2050,
                history.getNewTotalAmount());
    }

    @Test
    void changeItemsForUserDoesNothingWhenQuantitiesAreUnchanged() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrderForItemChange(
                orderId,
                userId);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        OrderItemChangeForm form = createOrderItemChangeForm(
                0,
                2,
                1);

        boolean changed = orderService.changeItemsForUser(
                orderId,
                userId,
                "testuser",
                form);

        assertFalse(changed);

        assertEquals(
                2,
                order.getItems().size());

        assertEquals(
                2,
                order.getItems().get(0).getQuantity());

        assertEquals(
                1,
                order.getItems().get(1).getQuantity());

        assertEquals(
                5500,
                order.getItemSubtotal());

        assertEquals(
                0,
                order.getChargeTotal());

        assertEquals(
                5500,
                order.getTotalAmount());

        assertEquals(
                0,
                order.getContentRevision());

        verifyNoInteractions(
                inventoryService);

        verifyNoInteractions(
                orderContentChangeHistoryRepository);

        verifyNoInteractions(
                orderAmountCalculator);
    }

    @Test
    void changeItemsForUserRejectsRemovingAllItems() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrderForItemChange(
                orderId,
                userId);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        OrderItemChangeForm form = createOrderItemChangeForm(
                0,
                0,
                0);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> orderService.changeItemsForUser(
                        orderId,
                        userId,
                        "testuser",
                        form));

        assertEquals(
                "注文には1点以上の商品が必要です。"
                        + "注文全体を取り消す場合は注文キャンセルを選択してください。",
                exception.getMessage());

        assertEquals(
                2,
                order.getItems().size());

        assertEquals(
                2,
                order.getItems().get(0).getQuantity());

        assertEquals(
                1,
                order.getItems().get(1).getQuantity());

        assertEquals(
                0,
                order.getContentRevision());

        verifyNoInteractions(
                inventoryService);

        verifyNoInteractions(
                orderContentChangeHistoryRepository);

        verifyNoInteractions(
                orderAmountCalculator);
    }

    @Test
    void changeItemsForUserRejectsQuantityIncrease() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrderForItemChange(
                orderId,
                userId);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        OrderItemChangeForm form = createOrderItemChangeForm(
                0,
                3,
                1);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> orderService.changeItemsForUser(
                        orderId,
                        userId,
                        "testuser",
                        form));

        assertEquals(
                "現在の注文数量を超える数量には変更できません。",
                exception.getMessage());

        assertEquals(
                2,
                order.getItems().get(0).getQuantity());

        assertEquals(
                1,
                order.getItems().get(1).getQuantity());

        assertEquals(
                0,
                order.getContentRevision());

        verifyNoInteractions(
                inventoryService);

        verifyNoInteractions(
                orderContentChangeHistoryRepository);

        verifyNoInteractions(
                orderAmountCalculator);
    }

    @Test
    void changeItemsForUserRejectsStaleContentRevision() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrderForItemChange(
                orderId,
                userId);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        OrderItemChangeForm form = createOrderItemChangeForm(
                1,
                1,
                1);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> orderService.changeItemsForUser(
                        orderId,
                        userId,
                        "testuser",
                        form));

        assertEquals(
                "注文内容が変更されています。最新の注文内容を確認して、もう一度操作してください。",
                exception.getMessage());

        assertEquals(
                2,
                order.getItems().get(0).getQuantity());

        assertEquals(
                1,
                order.getItems().get(1).getQuantity());

        assertEquals(
                0,
                order.getContentRevision());

        verifyNoInteractions(
                inventoryService);

        verifyNoInteractions(
                orderContentChangeHistoryRepository);

        verifyNoInteractions(
                orderAmountCalculator);
    }

    @Test
    void changeItemsForUserRejectsUnknownOrderItemId() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrderForItemChange(
                orderId,
                userId);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        OrderItemChangeForm form = createOrderItemChangeForm(
                0,
                1,
                1);

        form.getItems().get(0)
                .setOrderItemId(9999L);

        assertThrows(
                IllegalArgumentException.class,
                () -> orderService.changeItemsForUser(
                        orderId,
                        userId,
                        "testuser",
                        form));

        assertEquals(2, order.getItems().get(0).getQuantity());
        assertEquals(1, order.getItems().get(1).getQuantity());
        assertEquals(0, order.getContentRevision());

        verifyNoInteractions(inventoryService);
        verifyNoInteractions(orderContentChangeHistoryRepository);
        verifyNoInteractions(orderAmountCalculator);
    }

    @Test
    void changeItemsForUserRejectsDuplicateOrderItemId() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrderForItemChange(
                orderId,
                userId);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        OrderItemChangeForm form = createOrderItemChangeForm(
                0,
                1,
                1);

        form.getItems().get(1)
                .setOrderItemId(1001L);

        assertThrows(
                IllegalArgumentException.class,
                () -> orderService.changeItemsForUser(
                        orderId,
                        userId,
                        "testuser",
                        form));

        assertEquals(2, order.getItems().get(0).getQuantity());
        assertEquals(1, order.getItems().get(1).getQuantity());
        assertEquals(0, order.getContentRevision());

        verifyNoInteractions(inventoryService);
        verifyNoInteractions(orderContentChangeHistoryRepository);
        verifyNoInteractions(orderAmountCalculator);
    }

    @Test
    void changeItemsForUserRejectsExactlyAtDeadline() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrderForItemChange(
                orderId,
                userId);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        clock = Clock.fixed(
                LocalDateTime.of(
                        2026, 9, 28, 14, 0, 0)
                        .atZone(ZoneId.of("Asia/Tokyo"))
                        .toInstant(),
                ZoneId.of("Asia/Tokyo"));

        orderService = createOrderService();

        OrderItemChangeForm form = createOrderItemChangeForm(
                0,
                1,
                1);

        assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.changeItemsForUser(
                        orderId,
                        userId,
                        "testuser",
                        form));

        assertEquals(2, order.getItems().get(0).getQuantity());
        assertEquals(1, order.getItems().get(1).getQuantity());
        assertEquals(0, order.getContentRevision());

        verifyNoInteractions(inventoryService);
        verifyNoInteractions(orderContentChangeHistoryRepository);
        verifyNoInteractions(orderAmountCalculator);
    }

    @Test
    void changeItemsForUserRejectsPaidOrder() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = createOrderForItemChange(
                orderId,
                userId);

        order.markAsPaid();

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        OrderItemChangeForm form = createOrderItemChangeForm(
                0,
                1,
                1);

        assertThrows(
                InvalidOrderStatusException.class,
                () -> orderService.changeItemsForUser(
                        orderId,
                        userId,
                        "testuser",
                        form));

        assertEquals(0, order.getContentRevision());

        verifyNoInteractions(inventoryService);
        verifyNoInteractions(orderContentChangeHistoryRepository);
        verifyNoInteractions(orderAmountCalculator);
    }

    @Test
    void changeItemsForUserRejectsOrderOwnedByAnotherUser() {

        Long orderId = 1L;
        Long userId = 10L;

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.empty());

        OrderItemChangeForm form = createOrderItemChangeForm(
                0,
                1,
                1);

        assertThrows(
                OrderNotFoundException.class,
                () -> orderService.changeItemsForUser(
                        orderId,
                        userId,
                        "testuser",
                        form));

        verifyNoInteractions(inventoryService);
        verifyNoInteractions(orderContentChangeHistoryRepository);
        verifyNoInteractions(orderAmountCalculator);
    }

    @Test
    void cancelOrderForPaymentFailureRestoresStock() {

        Long orderId = 1L;
        Long productId = 20L;
        int quantity = 3;

        Order order = mock(Order.class);
        OrderItem orderItem = mock(OrderItem.class);

        when(order.getId())
                .thenReturn(orderId);

        when(order.getStatus())
                .thenReturn(
                        OrderStatus.ORDERED,
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

        OrderService orderService = createOrderService();

        orderService.cancelOrderForPaymentFailure(
                orderId);

        verify(order)
                .cancel(
                        LocalDateTime.of(
                                2026, 9, 28, 10, 0));

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
                        OrderStatusHistoryActorType.SYSTEM,
                        null,
                        "SYSTEM",
                        "カード与信失敗による注文キャンセル");
    }

    @Test
    void cancelOrderForPaymentFailureDoesNothingWhenAlreadyCancelled() {

        Long orderId = 1L;

        Order order = mock(Order.class);

        when(order.getStatus())
                .thenReturn(OrderStatus.CANCELLED);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        OrderService orderService = createOrderService();

        orderService.cancelOrderForPaymentFailure(
                orderId);

        verify(order, never())
                .cancel(any(LocalDateTime.class));

        verifyNoInteractions(inventoryService);

        verifyNoInteractions(orderStatusHistoryService);
    }

    private Product createProduct(
            Long id,
            String name,
            int price,
            int stock) {

        Category category = new Category("テストカテゴリ");

        org.springframework.test.util.ReflectionTestUtils
                .setField(category, "id", 100L);

        TaxCategory taxCategory = new TaxCategory();

        org.springframework.test.util.ReflectionTestUtils
                .setField(taxCategory, "id", 200L);

        taxCategory.setCode("STANDARD");
        taxCategory.setName("標準税率");
        taxCategory.setTaxRate(new BigDecimal("10.00"));
        taxCategory.setActive(true);
        taxCategory.setDisplayOrder(10);

        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setPrice(price);
        product.setStock(stock);
        product.setCategory(category);
        product.setTaxCategory(taxCategory);

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

    private OrderService createOrderService() {
        return new OrderService(
                orderRepository,
                productService,
                inventoryService,
                orderStatusHistoryService,
                orderHandlingStatusHistoryService,
                orderShippingAddressHistoryService,
                orderAssigneeHistoryService,
                orderContentChangeHistoryRepository,
                adminAccountService,
                orderDeadlineCalculator,
                taxCategoryService,
                orderAmountCalculator,
                clock);
    }

    private Order createOrder(
            Long userId,
            int totalAmount) {

        LocalDateTime orderedAt = LocalDateTime.of(2026, 9, 28, 10, 0);

        LocalDateTime changeDeadlineAt = orderDeadlineCalculator.calculate(orderedAt);

        return new Order(
                userId,
                totalAmount,
                orderedAt,
                changeDeadlineAt);
    }

    private OrderShippingAddressForm createOrderShippingAddressForm() {

        OrderShippingAddressForm form = new OrderShippingAddressForm();

        form.setShippingAddressMode(
                OrderShippingAddressForm.SHIPPING_ADDRESS_MODE_DIRECT);

        form.setShippingName(
                "佐藤 花子");

        form.setShippingPostalCode(
                "150-0001");

        form.setShippingPrefecture(
                "東京都");

        form.setShippingCity(
                "渋谷区");

        form.setShippingAddressLine(
                "神宮前1-2-3");

        form.setShippingPhone(
                "080-1234-5678");

        return form;
    }

    private TaxCategory createTaxCategory(
            Long id,
            String code,
            String name,
            BigDecimal taxRate) {

        TaxCategory taxCategory = new TaxCategory();

        org.springframework.test.util.ReflectionTestUtils
                .setField(taxCategory, "id", id);

        taxCategory.setCode(code);
        taxCategory.setName(name);
        taxCategory.setTaxRate(taxRate);
        taxCategory.setActive(true);
        taxCategory.setDisplayOrder(10);

        return taxCategory;
    }

    private void mockOrderAmountCalculation(
            int itemSubtotal,
            int shippingFee,
            int taxAmount,
            int totalAmount) {

        TaxCategory shippingTaxCategory = createTaxCategory(
                300L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"));

        when(taxCategoryService.findStandardTaxCategory())
                .thenReturn(shippingTaxCategory);

        OrderChargeAmount shippingCharge = new OrderChargeAmount(
                OrderChargeType.SHIPPING,
                "送料・梱包料",
                shippingFee,
                300L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"),
                10);

        OrderAmount orderAmount = new OrderAmount(
                itemSubtotal,
                List.of(shippingCharge),
                shippingFee,
                taxAmount,
                totalAmount);

        when(orderAmountCalculator.calculate(
                any(),
                same(shippingTaxCategory)))
                .thenReturn(orderAmount);
    }

    private Order createOrderForItemChange(
            Long orderId,
            Long userId) {

        LocalDateTime orderedAt = LocalDateTime.of(2026, 9, 28, 10, 0);

        LocalDateTime changeDeadlineAt = LocalDateTime.of(2026, 9, 28, 14, 0);

        Order order = new Order(
                userId,
                5500,
                orderedAt,
                changeDeadlineAt);

        org.springframework.test.util.ReflectionTestUtils
                .setField(order, "id", orderId);

        OrderItem firstItem = new OrderItem(
                10L,
                "商品A",
                100L,
                "テストカテゴリ",
                200L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"),
                2000,
                2);

        org.springframework.test.util.ReflectionTestUtils
                .setField(firstItem, "id", 1001L);

        OrderItem secondItem = new OrderItem(
                20L,
                "商品B",
                100L,
                "テストカテゴリ",
                200L,
                "STANDARD",
                "標準税率",
                new BigDecimal("10.00"),
                1500,
                1);

        org.springframework.test.util.ReflectionTestUtils
                .setField(secondItem, "id", 1002L);

        order.addItem(firstItem);
        order.addItem(secondItem);

        order.addCharge(
                new OrderCharge(
                        OrderChargeType.SHIPPING,
                        "送料・梱包料",
                        0,
                        300L,
                        "STANDARD",
                        "標準税率",
                        new BigDecimal("10.00"),
                        10));

        order.applyAmount(
                new OrderAmount(
                        5500,
                        List.of(
                                new OrderChargeAmount(
                                        OrderChargeType.SHIPPING,
                                        "送料・梱包料",
                                        0,
                                        300L,
                                        "STANDARD",
                                        "標準税率",
                                        new BigDecimal("10.00"),
                                        10)),
                        0,
                        500,
                        5500));

        return order;
    }

    private OrderItemChangeForm createOrderItemChangeForm(
            int contentRevision,
            int firstQuantity,
            int secondQuantity) {

        OrderItemChangeForm form = new OrderItemChangeForm();

        form.setContentRevision(
                contentRevision);

        OrderItemChangeForm.Item first = new OrderItemChangeForm.Item();

        first.setOrderItemId(1001L);
        first.setQuantity(firstQuantity);

        OrderItemChangeForm.Item second = new OrderItemChangeForm.Item();

        second.setOrderItemId(1002L);
        second.setQuantity(secondQuantity);

        form.setItems(
                List.of(first, second));

        return form;
    }

}
