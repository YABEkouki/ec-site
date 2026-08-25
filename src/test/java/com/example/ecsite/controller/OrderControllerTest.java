package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderService;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private Model model;

    @Mock
    private CustomUserDetails loginUser;

    private OrderController orderController;

    @BeforeEach
    void setUp() {
        orderController = new OrderController(orderService);
    }

    @Test
    void listDisplaysOrdersForLoggedInUser() {

        Long userId = 10L;

        Order order = new Order(userId, 2000);

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

        Order order = new Order(userId, 2000);

        when(loginUser.getId())
                .thenReturn(userId);

        when(orderService.findOrderByIdAndUserId(
                orderId,
                userId))
                .thenReturn(order);

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

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

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
                        userId);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文をキャンセルしました。");
    }

    @Test
    void cancelDisplaysErrorWhenOrderCannotBeCancelled() {

        Long orderId = 1L;
        Long userId = 10L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(loginUser.getId())
                .thenReturn(userId);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "注文受付中の注文だけを"
                        + "キャンセルできます。");

        doThrow(exception)
                .when(orderService)
                .cancelOrderForUser(
                        orderId,
                        userId);

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
}