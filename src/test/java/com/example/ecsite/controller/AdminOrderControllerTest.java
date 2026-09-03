package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.form.AdminOrderSearchForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderCsvService;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.OrderStatusHistoryService;

@ExtendWith(MockitoExtension.class)
class AdminOrderControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private Model model;

    @Mock
    private OrderCsvService orderCsvService;

    @Mock
    private OrderStatusHistoryService orderStatusHistoryService;

    @Mock
    private CustomUserDetails loginUser;

    private AdminOrderController adminOrderController;

    private static final Long ADMIN_ID = 20L;
    private static final String ADMIN_USERNAME = "admin";

    @BeforeEach
    void setUp() {
        adminOrderController = new AdminOrderController(
                orderService,
                orderCsvService,
                orderStatusHistoryService);
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
        OrderStatusHistory history = mock(OrderStatusHistory.class);

        List<OrderStatusHistory> statusHistories = List.of(history);

        when(orderService.findOrderWithItems(orderId))
                .thenReturn(order);

        when(orderStatusHistoryService.findByOrderId(orderId))
                .thenReturn(statusHistories);

        String viewName = adminOrderController.detail(
                orderId,
                model);

        assertEquals(
                "admin/orders/detail",
                viewName);

        verify(orderService)
                .findOrderWithItems(orderId);

        verify(orderStatusHistoryService)
                .findByOrderId(orderId);

        verify(model)
                .addAttribute(
                        "order",
                        order);

        verify(model)
                .addAttribute(
                        "statusHistories",
                        statusHistories);
    }

    @Test
    void markAsShippedChangesOrderStatusAndRedirectsToDetail() {

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = adminOrderController.markAsShipped(
                orderId,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService)
                .markAsShipped(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文を発送済みに変更しました。");
    }

    @Test
    void cancelCancelsOrderAndRedirectsToDetail() {

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = adminOrderController.cancel(
                orderId,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService)
                .cancelOrder(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文をキャンセルしました。");
    }

    @Test
    void markAsPaidDisplaysErrorWhenStatusIsInvalid() {

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "注文受付中の注文だけを支払済みに変更できます。");

        doThrow(exception)
                .when(orderService)
                .markAsPaid(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME);

        String viewName = adminOrderController.markAsPaid(
                orderId,
                loginUser,
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

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "支払済みの注文だけを発送済みに変更できます。");

        doThrow(exception)
                .when(orderService)
                .markAsShipped(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME);

        String viewName = adminOrderController.markAsShipped(
                orderId,
                loginUser,
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

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "注文受付中の注文だけをキャンセルできます。");

        doThrow(exception)
                .when(orderService)
                .cancelOrder(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME);

        String viewName = adminOrderController.cancel(
                orderId,
                loginUser,
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

    @Test
    void csvExportsAllOrdersMatchingSearchConditions() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();

        searchForm.setUserId(10L);
        searchForm.setStatus(OrderStatus.PAID);

        Order firstOrder = new Order(10L, 1000);
        Order secondOrder = new Order(10L, 2000);

        List<Order> orders = List.of(firstOrder, secondOrder);

        byte[] csvBytes = "csv-data".getBytes(
                StandardCharsets.UTF_8);

        when(orderService.searchAllOrders(searchForm))
                .thenReturn(orders);

        when(orderCsvService.createCsv(orders))
                .thenReturn(csvBytes);

        ResponseEntity<byte[]> response = adminOrderController.csv(searchForm);

        assertEquals(
                HttpStatus.OK,
                response.getStatusCode());

        assertEquals(
                "text/csv;charset=UTF-8",
                response.getHeaders()
                        .getFirst(
                                HttpHeaders.CONTENT_TYPE));

        assertEquals(
                "attachment; filename=\"orders.csv\"",
                response.getHeaders()
                        .getFirst(
                                HttpHeaders.CONTENT_DISPOSITION));

        assertArrayEquals(
                csvBytes,
                response.getBody());

        verify(orderService)
                .searchAllOrders(searchForm);

        verify(orderCsvService)
                .createCsv(orders);
    }
}
