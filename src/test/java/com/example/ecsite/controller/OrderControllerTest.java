package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Validator;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.dto.OrderItemChangePreview;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderContentChangeHistory;
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.entity.OrderShippingAddressHistory;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.form.OrderItemChangeForm;
import com.example.ecsite.form.OrderShippingAddressForm;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderContentChangeHistoryService;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.OrderShippingAddressHistoryService;
import com.example.ecsite.service.OrderStatusHistoryService;
import com.example.ecsite.service.ShippingAddressService;
import com.example.ecsite.service.payment.PaymentAuthorizationPreparation;
import com.example.ecsite.service.payment.PaymentAuthorizationService;
import com.example.ecsite.service.payment.PaymentCancellationService;
import com.example.ecsite.service.payment.PaymentService;

import jakarta.servlet.http.HttpSession;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private Model model;

    @Mock
    private CustomUserDetails loginUser;

    @Mock
    private OrderStatusHistoryService orderStatusHistoryService;

    @Mock
    private ShippingAddressService shippingAddressService;

    @Mock
    private OrderShippingAddressHistoryService orderShippingAddressHistoryService;

    @Mock
    private OrderContentChangeHistoryService orderContentChangeHistoryService;

    @Mock
    private Validator validator;

    @Mock
    private PaymentCancellationService paymentCancellationService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentAuthorizationService paymentAuthorizationService;

    private OrderController orderController;

    @BeforeEach
    void setUp() {
        orderController = new OrderController(
                orderService,
                orderStatusHistoryService,
                shippingAddressService,
                orderShippingAddressHistoryService,
                orderContentChangeHistoryService,
                validator,
                paymentCancellationService,
                paymentService,
                paymentAuthorizationService);
    }

    @Test
    void listDisplaysOrdersForLoggedInUser() {

        Long userId = 10L;

        Order order = new Order(
                userId,
                2000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        Page<Order> orderPage = new PageImpl<>(
                List.of(order));

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrdersByUserId(
                userId,
                0,
                10))
                .thenReturn(orderPage);

        String viewName = orderController.list(
                loginUser,
                0,
                10,
                model);

        assertEquals("orders/list", viewName);

        verify(orderService)
                .findOrdersByUserId(
                        userId,
                        0,
                        10);

        verify(model)
                .addAttribute(
                        "orders",
                        orderPage.getContent());

        verify(model)
                .addAttribute(
                        "orderPage",
                        orderPage);
    }

    @Test
    void detailDisplaysOrderForLoggedInUser() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                2000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        OrderStatusHistory history = mock(OrderStatusHistory.class);

        List<OrderStatusHistory> statusHistories = List.of(history);

        OrderShippingAddressHistory shippingAddressHistory = mock(OrderShippingAddressHistory.class);

        List<OrderShippingAddressHistory> shippingAddressHistories = List.of(shippingAddressHistory);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderStatusHistoryService.findByOrderId(orderId))
                .thenReturn(statusHistories);

        when(orderService.isWithinModificationPeriod(order))
                .thenReturn(true);

        when(orderService.canCancelByUser(order))
                .thenReturn(true);

        when(orderService.canChangeShippingAddress(order))
                .thenReturn(true);

        when(orderShippingAddressHistoryService.findByOrderId(orderId))
                .thenReturn(shippingAddressHistories);

        when(paymentService.canResumeAuthorization(order))
                .thenReturn(true);

        String viewName = orderController.detail(
                orderId,
                loginUser,
                model);

        assertEquals("orders/detail", viewName);

        verify(orderService)
                .findOrderByIdAndUserId(
                        orderId,
                        userId);

        verify(model)
                .addAttribute(
                        "order",
                        order);

        verify(orderStatusHistoryService)
                .findByOrderId(orderId);

        verify(model)
                .addAttribute(
                        "statusHistories",
                        statusHistories);

        verify(orderShippingAddressHistoryService)
                .findByOrderId(orderId);

        verify(model)
                .addAttribute(
                        "shippingAddressHistories",
                        shippingAddressHistories);

        verify(orderService)
                .isWithinModificationPeriod(order);

        verify(model)
                .addAttribute(
                        "withinModificationPeriod",
                        true);

        verify(orderService)
                .canCancelByUser(order);

        verify(orderService)
                .canChangeShippingAddress(order);

        verify(model)
                .addAttribute(
                        "canChangeShippingAddress",
                        true);

        verify(model)
                .addAttribute(
                        "canCancelByUser",
                        true);

        verify(paymentService)
                .canResumeAuthorization(order);

        verify(model)
                .addAttribute(
                        "canResumePayment",
                        true);
    }

    @Test
    void contentChangeHistoryDisplaysHistoriesForOwnedOrder() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                4000,
                LocalDateTime.of(2026, 9, 30, 10, 0),
                LocalDateTime.of(2026, 9, 30, 14, 0));

        OrderContentChangeHistory history = mock(OrderContentChangeHistory.class);

        List<OrderContentChangeHistory> histories = List.of(history);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderContentChangeHistoryService.findByOrderId(
                orderId))
                .thenReturn(histories);

        String viewName = orderController.contentChangeHistory(
                orderId,
                loginUser,
                model);

        assertEquals(
                "orders/content-change-history",
                viewName);

        verify(orderService)
                .findOrderByIdAndUserId(
                        orderId,
                        userId);

        verify(orderContentChangeHistoryService)
                .findByOrderId(orderId);

        verify(model)
                .addAttribute(
                        "order",
                        order);

        verify(model)
                .addAttribute(
                        "contentChangeHistories",
                        histories);
    }

    @Test
    void listAdjustsPageAndSizeWhenValuesAreTooSmall() {

        Long userId = 10L;

        Page<Order> orderPage = new PageImpl<>(
                List.of());

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrdersByUserId(
                userId,
                0,
                1))
                .thenReturn(orderPage);

        String viewName = orderController.list(
                loginUser,
                -1,
                0,
                model);

        assertEquals("orders/list", viewName);

        verify(orderService)
                .findOrdersByUserId(
                        userId,
                        0,
                        1);
    }

    @Test
    void listLimitsSizeToMaximum() {

        Long userId = 10L;

        Page<Order> orderPage = new PageImpl<>(
                List.of());

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrdersByUserId(
                userId,
                0,
                100))
                .thenReturn(orderPage);

        String viewName = orderController.list(
                loginUser,
                0,
                1000,
                model);

        assertEquals("orders/list", viewName);

        verify(orderService)
                .findOrdersByUserId(
                        userId,
                        0,
                        100);
    }

    @Test
    void cancelCancelsOwnedOrderAndRedirectsToDetail() {

        Long orderId = 1L;
        Long userId = 10L;
        String username = "testuser";

        when(loginUser.getId())
                .thenReturn(userId);

        when(loginUser.getUsername())
                .thenReturn(username);

        when(paymentCancellationService.cancelForUser(
                orderId,
                userId,
                username))
                .thenReturn(new CancellationResult(
                        CancellationResultStatus.CANCELLED,
                        "pf_test"));

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = orderController.cancel(
                orderId,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(paymentCancellationService)
                .cancelForUser(
                        orderId,
                        userId,
                        username);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文をキャンセルしました。");

    }

    @Test
    void cancelDisplaysErrorWhenOrderCannotBeCancelled() {

        Long orderId = 1L;
        Long userId = 10L;
        String username = "testuser";

        when(loginUser.getId())
                .thenReturn(userId);

        when(loginUser.getUsername())
                .thenReturn(username);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "注文受付中の注文だけをキャンセルできます。");

        when(paymentCancellationService.cancelForUser(
                orderId,
                userId,
                username))
                .thenThrow(exception);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = orderController.cancel(
                orderId,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(paymentCancellationService)
                .cancelForUser(
                        orderId,
                        userId,
                        username);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        exception.getMessage());
    }

    @Test
    void shippingAddressInputDisplaysFormWhenChangeIsAllowed() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                2000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeShippingAddress(order))
                .thenReturn(true);

        when(shippingAddressService.findAllByUserId(userId))
                .thenReturn(List.of());

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = orderController.shippingAddressInput(
                orderId,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "orders/shipping-address",
                viewName);

        verify(model)
                .addAttribute(
                        "order",
                        order);

        verify(model)
                .addAttribute(
                        "shippingAddresses",
                        List.of());
    }

    @Test
    void shippingAddressInputRedirectsWhenChangeIsNotAllowed() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                2000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeShippingAddress(order))
                .thenReturn(false);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = orderController.shippingAddressInput(
                orderId,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "現在、この注文の配送先は変更できません。");
    }

    @Test
    void changeShippingAddressUpdatesOwnedOrderAndRedirectsToDetail() {

        Long orderId = 1L;
        Long userId = 10L;

        OrderShippingAddressForm form = new OrderShippingAddressForm();

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        when(loginUser.getUsername())
                .thenReturn("testuser");

        when(orderService.changeShippingAddressForUser(
                orderId,
                userId,
                "testuser",
                form))
                .thenReturn(true);

        String viewName = orderController.changeShippingAddress(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(validator)
                .validate(
                        form,
                        bindingResult);

        verify(orderService)
                .changeShippingAddressForUser(
                        orderId,
                        userId,
                        "testuser",
                        form);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "配送先を変更しました。");
    }

    @Test
    void changeShippingAddressDisplaysErrorWhenServiceRejectsChange() {

        Long orderId = 1L;
        Long userId = 10L;

        OrderShippingAddressForm form = new OrderShippingAddressForm();

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        when(loginUser.getUsername())
                .thenReturn("testuser");

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "この注文の変更受付は終了しています。");

        doThrow(exception)
                .when(orderService)
                .changeShippingAddressForUser(
                        orderId,
                        userId,
                        "testuser",
                        form);

        String viewName = orderController.changeShippingAddress(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        exception.getMessage());
    }

    @Test
    void changeShippingAddressDisplaysNoChangeMessageWhenAddressIsUnchanged() {

        Long orderId = 1L;
        Long userId = 10L;

        OrderShippingAddressForm form = new OrderShippingAddressForm();

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(loginUser.getUsername())
                .thenReturn("testuser");

        when(bindingResult.hasErrors())
                .thenReturn(false);

        when(orderService.changeShippingAddressForUser(
                orderId,
                userId,
                "testuser",
                form))
                .thenReturn(false);

        String viewName = orderController.changeShippingAddress(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(validator)
                .validate(
                        form,
                        bindingResult);

        verify(orderService)
                .changeShippingAddressForUser(
                        orderId,
                        userId,
                        "testuser",
                        form);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "配送先に変更はありません。");
    }

    @Test
    void shippingAddressConfirmCopiesRegisteredAddressToForm() {

        Long orderId = 1L;
        Long userId = 10L;
        Long shippingAddressId = 20L;

        Order order = new Order(
                userId,
                2000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        OrderShippingAddressForm form = new OrderShippingAddressForm();

        form.setShippingAddressMode(
                OrderShippingAddressForm.SHIPPING_ADDRESS_MODE_REGISTERED);

        form.setShippingAddressId(
                shippingAddressId);

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        ShippingAddress address = mock(ShippingAddress.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeShippingAddress(order))
                .thenReturn(true);

        when(shippingAddressService.findByIdAndUserId(
                shippingAddressId,
                userId))
                .thenReturn(address);

        when(address.getId())
                .thenReturn(shippingAddressId);

        when(address.getRecipientName())
                .thenReturn("佐藤 花子");

        when(address.getPostalCode())
                .thenReturn("150-0001");

        when(address.getPrefecture())
                .thenReturn("東京都");

        when(address.getCity())
                .thenReturn("渋谷区");

        when(address.getAddressLine())
                .thenReturn("神宮前1-2-3");

        when(address.getPhone())
                .thenReturn("080-1234-5678");

        when(bindingResult.hasErrors())
                .thenReturn(false);

        String viewName = orderController.shippingAddressConfirm(
                orderId,
                form,
                bindingResult,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "orders/shipping-address-confirm",
                viewName);

        assertEquals(
                shippingAddressId,
                form.getShippingAddressId());

        assertEquals(
                OrderShippingAddressForm.SHIPPING_ADDRESS_MODE_REGISTERED,
                form.getShippingAddressMode());

        assertEquals(
                "佐藤 花子",
                form.getShippingName());

        assertEquals(
                "150-0001",
                form.getShippingPostalCode());

        assertEquals(
                "東京都",
                form.getShippingPrefecture());

        assertEquals(
                "渋谷区",
                form.getShippingCity());

        assertEquals(
                "神宮前1-2-3",
                form.getShippingAddressLine());

        assertEquals(
                "080-1234-5678",
                form.getShippingPhone());

        verify(shippingAddressService)
                .findByIdAndUserId(
                        shippingAddressId,
                        userId);

        verify(validator)
                .validate(
                        form,
                        bindingResult);

        verify(model)
                .addAttribute(
                        "order",
                        order);
    }

    @Test
    void shippingAddressConfirmUsesDirectInputWithoutRegisteredAddressLookup() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                2000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        OrderShippingAddressForm form = new OrderShippingAddressForm();

        form.setShippingAddressMode(
                OrderShippingAddressForm.SHIPPING_ADDRESS_MODE_DIRECT);

        form.setShippingName("佐藤 花子");
        form.setShippingPostalCode("150-0001");
        form.setShippingPrefecture("東京都");
        form.setShippingCity("渋谷区");
        form.setShippingAddressLine("神宮前1-2-3");
        form.setShippingPhone("080-1234-5678");

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeShippingAddress(order))
                .thenReturn(true);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        String viewName = orderController.shippingAddressConfirm(
                orderId,
                form,
                bindingResult,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "orders/shipping-address-confirm",
                viewName);

        assertEquals(
                null,
                form.getShippingAddressId());

        verifyNoInteractions(
                shippingAddressService);

        verify(validator)
                .validate(
                        form,
                        bindingResult);
    }

    @Test
    void shippingAddressConfirmRedirectsWhenChangeIsNotAllowed() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                2000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        OrderShippingAddressForm form = new OrderShippingAddressForm();

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeShippingAddress(order))
                .thenReturn(false);

        String viewName = orderController.shippingAddressConfirm(
                orderId,
                form,
                bindingResult,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "現在、この注文の配送先は変更できません。");
    }

    @Test
    void shippingAddressConfirmReturnsInputWhenValidationFails() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                2000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        OrderShippingAddressForm form = new OrderShippingAddressForm();

        form.setShippingAddressMode(
                OrderShippingAddressForm.SHIPPING_ADDRESS_MODE_DIRECT);

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeShippingAddress(order))
                .thenReturn(true);

        when(bindingResult.hasErrors())
                .thenReturn(true);

        when(shippingAddressService.findAllByUserId(userId))
                .thenReturn(List.of());

        String viewName = orderController.shippingAddressConfirm(
                orderId,
                form,
                bindingResult,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "orders/shipping-address",
                viewName);

        verify(validator)
                .validate(
                        form,
                        bindingResult);

        verify(model)
                .addAttribute(
                        "order",
                        order);

        verify(model)
                .addAttribute(
                        "shippingAddresses",
                        List.of());
    }

    @Test
    void cancelConfirmDisplaysConfirmationWhenCancellationIsAllowed() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                2000,
                LocalDateTime.of(2026, 9, 30, 10, 0),
                LocalDateTime.of(2026, 9, 30, 15, 0));

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canCancelByUser(order))
                .thenReturn(true);

        RedirectAttributes redirectAttributes = mock(
                RedirectAttributes.class);

        String viewName = orderController.cancelConfirm(
                orderId,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "orders/cancel-confirm",
                viewName);

        verify(orderService)
                .findOrderByIdAndUserId(
                        orderId,
                        userId);

        verify(orderService)
                .canCancelByUser(order);

        verify(model)
                .addAttribute(
                        "order",
                        order);
    }

    @Test
    void cancelConfirmRedirectsWhenCancellationIsNotAllowed() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                2000,
                LocalDateTime.of(2026, 9, 30, 10, 0),
                LocalDateTime.of(2026, 9, 30, 15, 0));

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canCancelByUser(order))
                .thenReturn(false);

        RedirectAttributes redirectAttributes = mock(
                RedirectAttributes.class);

        String viewName = orderController.cancelConfirm(
                orderId,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "現在、この注文はキャンセルできません。");

        verify(model, never())
                .addAttribute(
                        "order",
                        order);
    }

    @Test
    void itemChangeInputDisplaysFormWhenChangeIsAllowed() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                5500,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        OrderItem item = new OrderItem(
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

        ReflectionTestUtils.setField(
                item,
                "id",
                1001L);

        order.addItem(item);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeItemsByUser(order))
                .thenReturn(true);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = orderController.itemChangeInput(
                orderId,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "orders/item-change",
                viewName);

        verify(orderService)
                .findOrderByIdAndUserId(
                        orderId,
                        userId);

        verify(orderService)
                .canChangeItemsByUser(order);

        verify(model)
                .addAttribute(
                        "order",
                        order);

        verify(model)
                .addAttribute(
                        org.mockito.ArgumentMatchers.eq(
                                "orderItemChangeForm"),
                        org.mockito.ArgumentMatchers.any(
                                OrderItemChangeForm.class));
    }

    @Test
    void itemChangeInputRedirectsWhenChangeIsNotAllowed() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                5500,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeItemsByUser(order))
                .thenReturn(false);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = orderController.itemChangeInput(
                orderId,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(orderService)
                .findOrderByIdAndUserId(
                        orderId,
                        userId);

        verify(orderService)
                .canChangeItemsByUser(order);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "現在、この注文の商品内容は変更できません。");
    }

    @Test
    void itemChangeConfirmDisplaysConfirmationWhenChangeIsValid() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                5500,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        OrderItemChangeForm form = new OrderItemChangeForm();

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        OrderItemChangePreview preview = mock(OrderItemChangePreview.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeItemsByUser(order))
                .thenReturn(true);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        when(orderService.previewItemChangeForUser(
                orderId,
                userId,
                form))
                .thenReturn(preview);

        when(preview.isChanged())
                .thenReturn(true);

        String viewName = orderController.itemChangeConfirm(
                orderId,
                form,
                bindingResult,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "orders/item-change-confirm",
                viewName);

        verify(validator)
                .validate(
                        form,
                        bindingResult);

        verify(orderService)
                .previewItemChangeForUser(
                        orderId,
                        userId,
                        form);

        verify(model)
                .addAttribute(
                        "order",
                        order);

        verify(model)
                .addAttribute(
                        "preview",
                        preview);
    }

    @Test
    void itemChangeConfirmReturnsToInputWhenNothingChanged() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                5500,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        OrderItemChangeForm form = new OrderItemChangeForm();

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        OrderItemChangePreview preview = mock(OrderItemChangePreview.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeItemsByUser(order))
                .thenReturn(true);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        when(orderService.previewItemChangeForUser(
                orderId,
                userId,
                form))
                .thenReturn(preview);

        when(preview.isChanged())
                .thenReturn(false);

        String viewName = orderController.itemChangeConfirm(
                orderId,
                form,
                bindingResult,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "orders/item-change",
                viewName);

        verify(validator)
                .validate(
                        form,
                        bindingResult);

        verify(orderService)
                .previewItemChangeForUser(
                        orderId,
                        userId,
                        form);

        verify(bindingResult)
                .reject(
                        "unchanged",
                        "注文内容に変更がありません。");

        verify(model)
                .addAttribute(
                        "order",
                        order);
    }

    @Test
    void itemChangeBackReturnsToInputWithSubmittedForm() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                5500,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        OrderItemChangeForm form = new OrderItemChangeForm();

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeItemsByUser(order))
                .thenReturn(true);

        when(orderService.canCancelByUser(order))
                .thenReturn(true);

        String viewName = orderController.itemChangeBack(
                orderId,
                form,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "orders/item-change",
                viewName);

        verify(orderService)
                .findOrderByIdAndUserId(
                        orderId,
                        userId);

        verify(orderService)
                .canChangeItemsByUser(order);

        verify(model)
                .addAttribute(
                        "order",
                        order);

        verify(model)
                .addAttribute(
                        "canCancelByUser",
                        true);

        verifyNoInteractions(validator);
    }

    @Test
    void changeItemsUpdatesOwnedOrderAndRedirectsToDetail() {

        Long orderId = 1L;
        Long userId = 10L;
        String username = "testuser";

        OrderItemChangeForm form = new OrderItemChangeForm();

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        when(loginUser.getId())
                .thenReturn(userId);

        when(loginUser.getUsername())
                .thenReturn(username);

        when(orderService.changeItemsForUser(
                orderId,
                userId,
                username,
                form))
                .thenReturn(true);

        String viewName = orderController.changeItems(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(validator)
                .validate(
                        form,
                        bindingResult);

        verify(orderService)
                .changeItemsForUser(
                        orderId,
                        userId,
                        username,
                        form);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文内容を変更しました。");
    }

    @Test
    void changeItemsDisplaysErrorWhenContentRevisionIsStale() {

        Long orderId = 1L;
        Long userId = 10L;
        String username = "testuser";

        OrderItemChangeForm form = new OrderItemChangeForm();

        form.setContentRevision(0);

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        when(loginUser.getId())
                .thenReturn(userId);

        when(loginUser.getUsername())
                .thenReturn(username);

        IllegalStateException exception = new IllegalStateException(
                "注文内容が更新されています。もう一度確認してください。");

        when(orderService.changeItemsForUser(
                orderId,
                userId,
                username,
                form))
                .thenThrow(exception);

        String viewName = orderController.changeItems(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(validator)
                .validate(
                        form,
                        bindingResult);

        verify(orderService)
                .changeItemsForUser(
                        orderId,
                        userId,
                        username,
                        form);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        exception.getMessage());
    }

    @Test
    void changeItemsDoesNotCallServiceWhenFormHasValidationErrors() {

        Long orderId = 1L;

        OrderItemChangeForm form = new OrderItemChangeForm();

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(bindingResult.hasErrors())
                .thenReturn(true);

        String viewName = orderController.changeItems(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId + "/items",
                viewName);

        verify(validator)
                .validate(
                        form,
                        bindingResult);

        verify(orderService, never())
                .changeItemsForUser(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(
                                OrderItemChangeForm.class));

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "注文内容が正しくありません。もう一度入力してください。");
    }

    @Test
    void itemChangeConfirmReturnsToInputWhenFormHasValidationErrors() {

        Long orderId = 1L;
        Long userId = 10L;

        Order order = new Order(
                userId,
                5500,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        OrderItemChangeForm form = new OrderItemChangeForm();

        BindingResult bindingResult = mock(BindingResult.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(orderService.canChangeItemsByUser(order))
                .thenReturn(true);

        when(bindingResult.hasErrors())
                .thenReturn(true);

        when(orderService.canCancelByUser(order))
                .thenReturn(true);

        String viewName = orderController.itemChangeConfirm(
                orderId,
                form,
                bindingResult,
                loginUser,
                model,
                redirectAttributes);

        assertEquals(
                "orders/item-change",
                viewName);

        verify(validator)
                .validate(
                        form,
                        bindingResult);

        verify(orderService, never())
                .previewItemChangeForUser(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(
                                OrderItemChangeForm.class));

        verify(model)
                .addAttribute(
                        "order",
                        order);

        verify(model)
                .addAttribute(
                        "canCancelByUser",
                        true);
    }

    @Test
    void resumePaymentRestartsPendingAuthorizationAndRedirectsToPayment() {

        Long orderId = 100L;
        Long userId = 10L;
        Long paymentId = 20L;

        Order order = mock(Order.class);

        PaymentAuthorizationPreparation preparation = new PaymentAuthorizationPreparation(
                paymentId,
                "client-secret-123");

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        HttpSession session = mock(HttpSession.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(paymentService.canResumeAuthorization(order))
                .thenReturn(true);

        when(paymentAuthorizationService.prepareAuthorization(order))
                .thenReturn(preparation);

        String viewName = orderController.resumePayment(
                orderId,
                loginUser,
                redirectAttributes,
                session);

        assertEquals(
                "redirect:/checkout/payment",
                viewName);

        verify(orderService)
                .findOrderByIdAndUserId(
                        orderId,
                        userId);

        verify(paymentService)
                .canResumeAuthorization(order);

        verify(paymentAuthorizationService)
                .prepareAuthorization(order);

        verify(session)
                .setAttribute(
                        "checkoutOrderId",
                        orderId);

        verify(session)
                .setAttribute(
                        "checkoutPaymentId",
                        paymentId);

        verify(session)
                .setAttribute(
                        "checkoutPaymentClientSecret",
                        "client-secret-123");
    }

    @Test
    void resumePaymentRedirectsToDetailWhenAuthorizationCannotBeResumed() {

        Long orderId = 100L;
        Long userId = 10L;

        Order order = mock(Order.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        HttpSession session = mock(HttpSession.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(paymentService.canResumeAuthorization(order))
                .thenReturn(false);

        String viewName = orderController.resumePayment(
                orderId,
                loginUser,
                redirectAttributes,
                session);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(paymentAuthorizationService, never())
                .prepareAuthorization(
                        org.mockito.ArgumentMatchers.any());

        verify(session, never())
                .setAttribute(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "現在、この注文のカード決済は再開できません。");
    }

    @Test
    void resumePaymentRedirectsToDetailWhenPaymentGatewayFails() {

        Long orderId = 100L;
        Long userId = 10L;

        Order order = mock(Order.class);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        HttpSession session = mock(HttpSession.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

        when(paymentService.canResumeAuthorization(order))
                .thenReturn(true);

        when(paymentAuthorizationService.prepareAuthorization(order))
                .thenThrow(new PaymentGatewayException(
                        "PAY.JPとの通信に失敗しました。"));

        String viewName = orderController.resumePayment(
                orderId,
                loginUser,
                redirectAttributes,
                session);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(session, never())
                .setAttribute(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "カード決済を開始できませんでした。しばらくしてからもう一度お試しください。");
    }

}