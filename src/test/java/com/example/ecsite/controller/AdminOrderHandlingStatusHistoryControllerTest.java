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
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;

import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderHandlingStatusHistory;
import com.example.ecsite.form.AdminOrderHandlingStatusHistorySearchForm;
import com.example.ecsite.service.OrderHandlingStatusHistoryCsvService;
import com.example.ecsite.service.OrderHandlingStatusHistoryService;

@ExtendWith(MockitoExtension.class)
class AdminOrderHandlingStatusHistoryControllerTest {

    @Mock
    private OrderHandlingStatusHistoryService service;

    @Mock
    private Model model;

    @Mock
    private OrderHandlingStatusHistoryCsvService csvService;

    private AdminOrderHandlingStatusHistoryController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminOrderHandlingStatusHistoryController(
                service,
                csvService);
    }

    @Test
    void listDisplaysHistoriesUsingSearchForm() {

        AdminOrderHandlingStatusHistorySearchForm searchForm = new AdminOrderHandlingStatusHistorySearchForm();

        OrderHandlingStatusHistory history = org.mockito.Mockito.mock(
                OrderHandlingStatusHistory.class);

        Page<OrderHandlingStatusHistory> historyPage = new PageImpl<>(List.of(history));

        when(service.search(searchForm, 0, 10))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                0,
                10,
                model);

        assertEquals(
                "admin/order-handling-status-histories/list",
                viewName);

        verify(service).search(
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
                OrderHandlingStatus.values());
    }

    @Test
    void listPassesSearchConditionsToService() {

        AdminOrderHandlingStatusHistorySearchForm searchForm = new AdminOrderHandlingStatusHistorySearchForm();

        searchForm.setOrderId(10L);
        searchForm.setFromStatus(
                OrderHandlingStatus.NONE);
        searchForm.setToStatus(
                OrderHandlingStatus.NEEDS_ACTION);
        searchForm.setChangedByUsername(
                "AdminUser");

        Page<OrderHandlingStatusHistory> historyPage = new PageImpl<>(List.of());

        when(service.search(searchForm, 0, 10))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                0,
                10,
                model);

        assertEquals(
                "admin/order-handling-status-histories/list",
                viewName);

        verify(service).search(
                searchForm,
                0,
                10);
    }

    @Test
    void listSanitizesPageAndSize() {

        AdminOrderHandlingStatusHistorySearchForm searchForm = new AdminOrderHandlingStatusHistorySearchForm();

        Page<OrderHandlingStatusHistory> historyPage = new PageImpl<>(List.of());

        when(service.search(
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
                "admin/order-handling-status-histories/list",
                viewName);

        verify(service).search(
                searchForm,
                0,
                100);
    }

    @Test
    void listSanitizesSizeLessThanOne() {

        AdminOrderHandlingStatusHistorySearchForm searchForm = new AdminOrderHandlingStatusHistorySearchForm();

        Page<OrderHandlingStatusHistory> historyPage = new PageImpl<>(List.of());

        when(service.search(
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
                "admin/order-handling-status-histories/list",
                viewName);

        verify(service).search(
                searchForm,
                0,
                1);
    }

    @Test
    void csvOutputsAllHistoriesMatchingSearchConditions() {

        AdminOrderHandlingStatusHistorySearchForm searchForm = new AdminOrderHandlingStatusHistorySearchForm();

        OrderHandlingStatusHistory history = org.mockito.Mockito.mock(
                OrderHandlingStatusHistory.class);

        List<OrderHandlingStatusHistory> histories = List.of(history);

        byte[] csvBytes = "csv-data".getBytes();

        when(service.searchAll(searchForm))
                .thenReturn(histories);

        when(csvService.createCsv(histories))
                .thenReturn(csvBytes);

        ResponseEntity<byte[]> response = controller.csv(searchForm);

        assertEquals(200, response.getStatusCode().value());

        assertEquals(
                "text/csv;charset=UTF-8",
                response.getHeaders()
                        .getFirst(HttpHeaders.CONTENT_TYPE));

        assertEquals(
                "attachment; filename=\"order-handling-status-histories.csv\"",
                response.getHeaders()
                        .getFirst(HttpHeaders.CONTENT_DISPOSITION));

        assertArrayEquals(
                csvBytes,
                response.getBody());

        verify(service).searchAll(searchForm);
        verify(csvService).createCsv(histories);
    }

}
