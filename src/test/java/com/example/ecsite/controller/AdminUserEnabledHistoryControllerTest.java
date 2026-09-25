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

import com.example.ecsite.entity.UserEnabledHistory;
import com.example.ecsite.form.AdminUserEnabledHistorySearchForm;
import com.example.ecsite.service.UserEnabledHistoryCsvService;
import com.example.ecsite.service.UserEnabledHistoryService;

@ExtendWith(MockitoExtension.class)
class AdminUserEnabledHistoryControllerTest {

    @Mock
    private UserEnabledHistoryService userEnabledHistoryService;

    @Mock
    private UserEnabledHistoryCsvService userEnabledHistoryCsvService;

    @Mock
    private Model model;

    private AdminUserEnabledHistoryController controller;

    @BeforeEach
    void setUp() {

        controller = new AdminUserEnabledHistoryController(
                userEnabledHistoryService,
                userEnabledHistoryCsvService);
    }

    @Test
    void listDisplaysHistoriesUsingSearchForm() {

        AdminUserEnabledHistorySearchForm searchForm = new AdminUserEnabledHistorySearchForm();

        UserEnabledHistory history = org.mockito.Mockito.mock(
                UserEnabledHistory.class);

        Page<UserEnabledHistory> historyPage = new PageImpl<>(List.of(history));

        when(userEnabledHistoryService.search(
                searchForm,
                0,
                10))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                null,
                0,
                10,
                model);

        assertEquals(
                "admin/user-enabled-histories/list",
                viewName);

        verify(userEnabledHistoryService).search(
                searchForm,
                0,
                10);

        verify(model).addAttribute(
                "histories",
                historyPage.getContent());

        verify(model).addAttribute(
                "historyPage",
                historyPage);
    }

    @Test
    void listPassesSearchConditionsToService() {

        AdminUserEnabledHistorySearchForm searchForm = new AdminUserEnabledHistorySearchForm();

        searchForm.setUserId(10L);
        searchForm.setUsername("customer");
        searchForm.setToEnabled(false);
        searchForm.setChangedByUsername("AdminUser");

        Page<UserEnabledHistory> historyPage = new PageImpl<>(List.of());

        when(userEnabledHistoryService.search(
                searchForm,
                2,
                20))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                null,
                2,
                20,
                model);

        assertEquals(
                "admin/user-enabled-histories/list",
                viewName);

        verify(userEnabledHistoryService).search(
                searchForm,
                2,
                20);
    }

    @Test
    void listSanitizesPageAndSize() {

        AdminUserEnabledHistorySearchForm searchForm = new AdminUserEnabledHistorySearchForm();

        Page<UserEnabledHistory> historyPage = new PageImpl<>(List.of());

        when(userEnabledHistoryService.search(
                searchForm,
                0,
                100))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                null,
                -1,
                999,
                model);

        assertEquals(
                "admin/user-enabled-histories/list",
                viewName);

        verify(userEnabledHistoryService).search(
                searchForm,
                0,
                100);
    }

    @Test
    void listSanitizesSizeLessThanOne() {

        AdminUserEnabledHistorySearchForm searchForm = new AdminUserEnabledHistorySearchForm();

        Page<UserEnabledHistory> historyPage = new PageImpl<>(List.of());

        when(userEnabledHistoryService.search(
                searchForm,
                0,
                1))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                null,
                0,
                0,
                model);

        assertEquals(
                "admin/user-enabled-histories/list",
                viewName);

        verify(userEnabledHistoryService).search(
                searchForm,
                0,
                1);
    }

    @Test
    void csvExportsAllHistoriesMatchingSearchConditions() {

        AdminUserEnabledHistorySearchForm searchForm = new AdminUserEnabledHistorySearchForm();

        searchForm.setUserId(10L);
        searchForm.setUsername("customer");
        searchForm.setToEnabled(true);
        searchForm.setChangedByUsername("AdminUser");

        UserEnabledHistory history = org.mockito.Mockito.mock(
                UserEnabledHistory.class);

        List<UserEnabledHistory> histories = List.of(history);

        byte[] csvBytes = new byte[] { 1, 2, 3 };

        when(userEnabledHistoryService.searchAll(searchForm))
                .thenReturn(histories);

        when(userEnabledHistoryCsvService.createCsv(histories))
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
                "attachment; filename=\"user-enabled-histories.csv\"",
                response.getHeaders()
                        .getFirst(HttpHeaders.CONTENT_DISPOSITION));

        assertArrayEquals(
                csvBytes,
                response.getBody());

        verify(userEnabledHistoryService)
                .searchAll(searchForm);

        verify(userEnabledHistoryCsvService)
                .createCsv(histories);
    }

    @Test
    void listAddsReturnToCustomerToModel() {

        AdminUserEnabledHistorySearchForm searchForm = new AdminUserEnabledHistorySearchForm();

        searchForm.setUserId(14L);

        Page<UserEnabledHistory> historyPage = new PageImpl<>(List.of());

        when(userEnabledHistoryService.search(
                searchForm,
                0,
                10))
                .thenReturn(historyPage);

        String viewName = controller.list(
                searchForm,
                "customer",
                0,
                10,
                model);

        assertEquals(
                "admin/user-enabled-histories/list",
                viewName);

        verify(model).addAttribute(
                "returnTo",
                "customer");
    }
}
