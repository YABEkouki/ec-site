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
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.form.AdminOrderSearchForm;
import com.example.ecsite.service.OrderService;

@ExtendWith(MockitoExtension.class)
class AdminOrderControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private Model model;

    private AdminOrderController adminOrderController;

    @BeforeEach
    void setUp() {
        adminOrderController = new AdminOrderController(orderService);
    }

    @Test
    void listDisplaysOrdersUsingSearchForm() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();

        Order order = new Order(10L, 2000);
        Page<Order> orderPage = new PageImpl<>(List.of(order));

        when(orderService.searchOrders(
                searchForm,
                0,
                10))
                .thenReturn(orderPage);

        String viewName = adminOrderController.list(
                searchForm,
                0,
                10,
                model);

        assertEquals(
                "admin/orders/list",
                viewName);

        verify(orderService).searchOrders(
                searchForm,
                0,
                10);

        verify(model).addAttribute(
                "orders",
                orderPage.getContent());

        verify(model).addAttribute(
                "orderPage",
                orderPage);

        verify(model).addAttribute(
                "statuses",
                OrderStatus.values());
    }

    @Test
    void listFiltersOrdersByStatus() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();
        searchForm.setStatus(OrderStatus.PAID);

        Order order = new Order(10L, 2000);
        Page<Order> orderPage = new PageImpl<>(List.of(order));

        when(orderService.searchOrders(
                searchForm,
                0,
                10))
                .thenReturn(orderPage);

        String viewName = adminOrderController.list(
                searchForm,
                0,
                10,
                model);

        assertEquals(
                "admin/orders/list",
                viewName);

        verify(orderService).searchOrders(
                searchForm,
                0,
                10);

        verify(model).addAttribute(
                "statuses",
                OrderStatus.values());
    }

    @Test
    void detailDisplaysOrderWithItems() {

        Long orderId = 1L;

        Order order = new Order(10L, 2000);

        when(orderService.findOrderWithItems(orderId))
                .thenReturn(order);

        String viewName = adminOrderController.detail(
                orderId,
                model);

        assertEquals(
                "admin/orders/detail",
                viewName);

        verify(orderService)
                .findOrderWithItems(orderId);

        verify(model)
                .addAttribute(
                        "order",
                        order);
    }

    @Test
    void markAsPaidChangesOrderStatusAndRedirectsToDetail() {

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = adminOrderController.markAsPaid(
                orderId,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService)
                .markAsPaid(orderId);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文を支払済みに変更しました。");
    }

    @Test
    void markAsShippedChangesOrderStatusAndRedirectsToDetail() {

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = adminOrderController.markAsShipped(
                orderId,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService)
                .markAsShipped(orderId);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文を発送済みに変更しました。");
    }

    @Test
    void cancelCancelsOrderAndRedirectsToDetail() {

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = adminOrderController.cancel(
                orderId,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService)
                .cancelOrder(orderId);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文をキャンセルしました。");
    }

    @Test
    void markAsPaidDisplaysErrorWhenStatusIsInvalid() {

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "注文受付中の注文だけを支払済みに変更できます。");

        doThrow(exception)
                .when(orderService)
                .markAsPaid(orderId);

        String viewName = adminOrderController.markAsPaid(
                orderId,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        exception.getMessage());
    }

    @Test
    void markAsShippedDisplaysErrorWhenStatusIsInvalid() {

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "支払済みの注文だけを発送済みに変更できます。");

        doThrow(exception)
                .when(orderService)
                .markAsShipped(orderId);

        String viewName = adminOrderController.markAsShipped(
                orderId,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        exception.getMessage());
    }

    @Test
    void cancelDisplaysErrorWhenStatusIsInvalid() {

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "注文受付中の注文だけをキャンセルできます。");

        doThrow(exception)
                .when(orderService)
                .cancelOrder(orderId);

        String viewName = adminOrderController.cancel(
                orderId,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        exception.getMessage());
    }

    @Test
    void listSanitizesPageAndSize() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();

        Page<Order> orderPage = new PageImpl<>(List.of());

        when(orderService.searchOrders(
                searchForm,
                0,
                100))
                .thenReturn(orderPage);

        String viewName = adminOrderController.list(
                searchForm,
                -1,
                999,
                model);

        assertEquals(
                "admin/orders/list",
                viewName);

        verify(orderService).searchOrders(
                searchForm,
                0,
                100);
    }
}
