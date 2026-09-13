package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;

import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.entity.OrderAssigneeHistory;
import com.example.ecsite.form.AdminOrderAssigneeHistoryFilter;
import com.example.ecsite.form.AdminOrderAssigneeHistorySearchForm;
import com.example.ecsite.service.AdminAccountService;
import com.example.ecsite.service.OrderAssigneeHistoryCsvService;
import com.example.ecsite.service.OrderAssigneeHistoryService;

@ExtendWith(MockitoExtension.class)
class AdminOrderAssigneeHistoryControllerTest {

    @Mock
    private OrderAssigneeHistoryService service;

    @Mock
    private AdminAccountService adminAccountService;

    @Mock
    private Model model;

    @Mock
    private OrderAssigneeHistoryCsvService orderAssigneeHistoryCsvService;

    private AdminOrderAssigneeHistoryController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminOrderAssigneeHistoryController(
                service,
                adminAccountService,
                orderAssigneeHistoryCsvService);
    }

    @Test
    void listDisplaysHistoriesAndAdminAccounts() {

        AdminOrderAssigneeHistorySearchForm searchForm = new AdminOrderAssigneeHistorySearchForm();

        OrderAssigneeHistory history = org.mockito.Mockito.mock(
                OrderAssigneeHistory.class);

        Page<OrderAssigneeHistory> historyPage = new PageImpl<>(List.of(history));

        AdminAccount adminAccount = org.mockito.Mockito.mock(
                AdminAccount.class);

        List<AdminAccount> adminAccounts = List.of(adminAccount);

        when(service.search(
                searchForm,
                0,
                10))
                .thenReturn(historyPage);

        when(adminAccountService
                .findAllOrderByUsernameAsc())
                .thenReturn(adminAccounts);

        String viewName = controller.list(
                searchForm,
                0,
                10,
                model);

        assertEquals(
                "admin/order-assignee-histories/list",
                viewName);

        verify(service).search(
                searchForm,
                0,
                10);

        verify(adminAccountService)
                .findAllOrderByUsernameAsc();

        verify(model).addAttribute(
                "histories",
                historyPage.getContent());

        verify(model).addAttribute(
                "historyPage",
                historyPage);

        verify(model).addAttribute(
                "adminAccounts",
                adminAccounts);

        verify(model).addAttribute(
                "assigneeFilters",
                AdminOrderAssigneeHistoryFilter.values());
    }

    @Test
    void listPassesSearchConditionsToService() {

        AdminOrderAssigneeHistorySearchForm searchForm = new AdminOrderAssigneeHistorySearchForm();

        searchForm.setOrderId(10L);

        searchForm.setFromAssigneeFilter(
                AdminOrderAssigneeHistoryFilter.SPECIFIC);
        searchForm.setFromAdminAccountId(11L);

        searchForm.setToAssigneeFilter(
                AdminOrderAssigneeHistoryFilter.UNASSIGNED);

        searchForm.setChangedByUsername(
                "AdminUser");

        Page<OrderAssigneeHistory> historyPage = new PageImpl<>(List.of());

        when(service.search(
                searchForm,
                0,
                10))
                .thenReturn(historyPage);

        when(adminAccountService
                .findAllOrderByUsernameAsc())
                .thenReturn(List.of());

        String viewName = controller.list(
                searchForm,
                0,
                10,
                model);

        assertEquals(
                "admin/order-assignee-histories/list",
                viewName);

        verify(service).search(
                searchForm,
                0,
                10);
    }

    @Test
    void listSanitizesPageAndSize() {

        AdminOrderAssigneeHistorySearchForm searchForm = new AdminOrderAssigneeHistorySearchForm();

        Page<OrderAssigneeHistory> historyPage = new PageImpl<>(List.of());

        when(service.search(
                searchForm,
                0,
                100))
                .thenReturn(historyPage);

        when(adminAccountService
                .findAllOrderByUsernameAsc())
                .thenReturn(List.of());

        String viewName = controller.list(
                searchForm,
                -1,
                999,
                model);

        assertEquals(
                "admin/order-assignee-histories/list",
                viewName);

        verify(service).search(
                searchForm,
                0,
                100);
    }

    @Test
    void listSanitizesSizeLessThanOne() {

        AdminOrderAssigneeHistorySearchForm searchForm = new AdminOrderAssigneeHistorySearchForm();

        Page<OrderAssigneeHistory> historyPage = new PageImpl<>(List.of());

        when(service.search(
                searchForm,
                0,
                1))
                .thenReturn(historyPage);

        when(adminAccountService
                .findAllOrderByUsernameAsc())
                .thenReturn(List.of());

        String viewName = controller.list(
                searchForm,
                0,
                0,
                model);

        assertEquals(
                "admin/order-assignee-histories/list",
                viewName);

        verify(service).search(
                searchForm,
                0,
                1);
    }

    @Test
    void csvOutputsAllHistoriesMatchingSearchConditions() {

        AdminOrderAssigneeHistorySearchForm searchForm = new AdminOrderAssigneeHistorySearchForm();

        OrderAssigneeHistory history = mock(OrderAssigneeHistory.class);

        List<OrderAssigneeHistory> histories = List.of(history);

        byte[] csvBytes = new byte[] { 1, 2, 3 };

        when(service.searchAll(searchForm))
                .thenReturn(histories);

        when(orderAssigneeHistoryCsvService.createCsv(histories))
                .thenReturn(csvBytes);

        ResponseEntity<byte[]> response = controller.csv(searchForm);

        assertEquals(
                200,
                response.getStatusCode().value());

        assertEquals(
                "text/csv;charset=UTF-8",
                response.getHeaders()
                        .getContentType()
                        .toString());

        assertEquals(
                "attachment; filename=\"order-assignee-histories.csv\"",
                response.getHeaders()
                        .getFirst("Content-Disposition"));

        assertArrayEquals(
                csvBytes,
                response.getBody());

        verify(service)
                .searchAll(searchForm);

        verify(orderAssigneeHistoryCsvService)
                .createCsv(histories);
    }

}
