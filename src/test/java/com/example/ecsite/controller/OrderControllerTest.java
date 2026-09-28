package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Validator;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderShippingAddressHistory;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.form.OrderShippingAddressForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.OrderShippingAddressHistoryService;
import com.example.ecsite.service.OrderStatusHistoryService;
import com.example.ecsite.service.ShippingAddressService;

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
    private Validator validator;

    private OrderController orderController;

    @BeforeEach
    void setUp() {
        orderController = new OrderController(
                orderService,
                orderStatusHistoryService,
                shippingAddressService,
                orderShippingAddressHistoryService,
                validator);
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
        String username = "user1";

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(loginUser.getUsername())
                .thenReturn(username);

        String viewName = orderController.cancel(
                orderId,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/orders/" + orderId,
                viewName);

        verify(orderService)
                .cancelOrderForUser(
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
        String username = "user1";

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        when(loginUser.getUsername())
                .thenReturn(username);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "注文受付中の注文だけを"
                        + "キャンセルできます。");

        doThrow(exception)
                .when(orderService)
                .cancelOrderForUser(
                        orderId,
                        userId,
                        username);

        String viewName = orderController.cancel(
                orderId,
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

}