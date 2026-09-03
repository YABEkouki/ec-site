package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;

import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.form.AdminOrderStatusHistorySearchForm;
import com.example.ecsite.service.OrderStatusHistoryCsvService;
import com.example.ecsite.service.OrderStatusHistoryService;

@ExtendWith(MockitoExtension.class)
class AdminOrderStatusHistoryControllerTest {

    @Mock
    private OrderStatusHistoryService orderStatusHistoryService;

    @Mock
    private OrderStatusHistoryCsvService orderStatusHistoryCsvService;

    @Mock
    private Model model;

    private AdminOrderStatusHistoryController controller;

    @BeforeEach
    void setUp() {

        controller = new AdminOrderStatusHistoryController(
                orderStatusHistoryService,
                orderStatusHistoryCsvService);
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

    @Test
    void csvExportsAllHistoriesMatchingSearchConditions() {

        AdminOrderStatusHistorySearchForm searchForm = new AdminOrderStatusHistorySearchForm();

        searchForm.setOrderId(10L);
        searchForm.setFromStatus(OrderStatus.ORDERED);
        searchForm.setToStatus(OrderStatus.PAID);
        searchForm.setChangedByType(
                OrderStatusHistoryActorType.ADMIN);
        searchForm.setChangedByUsername("AdminUser");

        OrderStatusHistory history = org.mockito.Mockito.mock(
                OrderStatusHistory.class);

        List<OrderStatusHistory> histories = List.of(history);

        byte[] csvBytes = new byte[] { 1, 2, 3 };

        when(orderStatusHistoryService.searchAll(searchForm))
                .thenReturn(histories);

        when(orderStatusHistoryCsvService.createCsv(histories))
                .thenReturn(csvBytes);

        ResponseEntity<byte[]> response = controller.csv(searchForm);

        assertEquals(
                HttpStatus.OK,
                response.getStatusCode());

        assertEquals(
                "text/csv;charset=UTF-8",
                response.getHeaders()
                        .getFirst(HttpHeaders.CONTENT_TYPE));

        assertEquals(
                "attachment; filename=\"order-status-histories.csv\"",
                response.getHeaders()
                        .getFirst(HttpHeaders.CONTENT_DISPOSITION));

        assertArrayEquals(
                csvBytes,
                response.getBody());

        verify(orderStatusHistoryService)
                .searchAll(searchForm);

        verify(orderStatusHistoryCsvService)
                .createCsv(histories);
    }

}
