package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.form.AdminOrderStatusHistorySearchForm;
import com.example.ecsite.service.OrderStatusHistoryService;

@ExtendWith(MockitoExtension.class)
class AdminOrderStatusHistoryControllerTest {

    @Mock
    private OrderStatusHistoryService orderStatusHistoryService;

    @Mock
    private Model model;

    private AdminOrderStatusHistoryController controller;

    @BeforeEach
    void setUp() {

        controller = new AdminOrderStatusHistoryController(
                orderStatusHistoryService);
    }

    @Test
    void listDisplaysHistoriesUsingSearchForm() {

        AdminOrderStatusHistorySearchForm searchForm = new AdminOrderStatusHistorySearchForm();

        OrderStatusHistory history = org.mockito.Mockito.mock(
                OrderStatusHistory.class);

        Page<OrderStatusHistory> historyPage = new PageImpl<>(List.of(history));

        when(orderStatusHistoryService.search(
                searchForm,
                0,
                10))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                0,
                10,
                model);

        assertEquals(
                "admin/order-status-histories/list",
                viewName);

        verify(orderStatusHistoryService).search(
                searchForm,
                0,
                10);

        verify(model).addAttribute(
                "histories",
                historyPage.getContent());

        verify(model).addAttribute(
                "historyPage",
                historyPage);

        verify(model).addAttribute(
                "statuses",
                OrderStatus.values());

        verify(model).addAttribute(
                "actorTypes",
                OrderStatusHistoryActorType.values());
    }

    @Test
    void listPassesSearchConditionsToService() {

        AdminOrderStatusHistorySearchForm searchForm = new AdminOrderStatusHistorySearchForm();

        searchForm.setOrderId(10L);
        searchForm.setFromStatus(OrderStatus.ORDERED);
        searchForm.setToStatus(OrderStatus.PAID);
        searchForm.setChangedByType(
                OrderStatusHistoryActorType.ADMIN);
        searchForm.setChangedByUsername("AdminUser");

        Page<OrderStatusHistory> historyPage = new PageImpl<>(List.of());

        when(orderStatusHistoryService.search(
                searchForm,
                0,
                10))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                0,
                10,
                model);

        assertEquals(
                "admin/order-status-histories/list",
                viewName);

        verify(orderStatusHistoryService).search(
                searchForm,
                0,
                10);
    }

    @Test
    void listSanitizesPageAndSize() {

        AdminOrderStatusHistorySearchForm searchForm = new AdminOrderStatusHistorySearchForm();

        Page<OrderStatusHistory> historyPage = new PageImpl<>(List.of());

        when(orderStatusHistoryService.search(
                searchForm,
                0,
                100))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                -1,
                999,
                model);

        assertEquals(
                "admin/order-status-histories/list",
                viewName);

        verify(orderStatusHistoryService).search(
                searchForm,
                0,
                100);
    }

    @Test
    void listSanitizesSizeLessThanOne() {

        AdminOrderStatusHistorySearchForm searchForm = new AdminOrderStatusHistorySearchForm();

        Page<OrderStatusHistory> historyPage = new PageImpl<>(List.of());

        when(orderStatusHistoryService.search(
                searchForm,
                0,
                1))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                0,
                0,
                model);

        assertEquals(
                "admin/order-status-histories/list",
                viewName);

        verify(orderStatusHistoryService).search(
                searchForm,
                0,
                1);
    }
}
