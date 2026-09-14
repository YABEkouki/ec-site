package com.example.ecsite.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.dto.AdminCustomerListItem;
import com.example.ecsite.form.AdminCustomerSearchForm;
import com.example.ecsite.service.AdminCustomerService;

@WebMvcTest(AdminCustomerController.class)
class AdminCustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminCustomerService adminCustomerService;

    @Test
    void listReturnsCustomerListView() throws Exception {

        AdminCustomerListItem customer = new AdminCustomerListItem(
                1L,
                "customer01",
                "山田太郎",
                true);

        when(adminCustomerService.searchCustomers(
                any(AdminCustomerSearchForm.class),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(10)))
                .thenReturn(
                        new PageImpl<>(
                                List.of(customer)));

        mockMvc.perform(
                get("/admin/customers")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(
                        view().name(
                                "admin/customers/list"))
                .andExpect(
                        model().attributeExists(
                                "searchForm"))
                .andExpect(
                        model().attributeExists(
                                "customerPage"))
                .andExpect(
                        model().attribute(
                                "customers",
                                hasSize(1)));
    }

    @Test
    void listPassesSearchConditionsToService() throws Exception {

        when(adminCustomerService.searchCustomers(
                any(AdminCustomerSearchForm.class),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(10)))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/customers")
                        .param("userId", "123")
                        .param("username", "customer")
                        .param("name", "山田")
                        .param("enabled", "true")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<AdminCustomerSearchForm> formCaptor = org.mockito.ArgumentCaptor.forClass(
                AdminCustomerSearchForm.class);

        verify(adminCustomerService)
                .searchCustomers(
                        formCaptor.capture(),
                        org.mockito.ArgumentMatchers.eq(0),
                        org.mockito.ArgumentMatchers.eq(10));

        AdminCustomerSearchForm form = formCaptor.getValue();

        org.junit.jupiter.api.Assertions.assertEquals(
                123L,
                form.getUserId());

        org.junit.jupiter.api.Assertions.assertEquals(
                "customer",
                form.getUsername());

        org.junit.jupiter.api.Assertions.assertEquals(
                "山田",
                form.getName());

        org.junit.jupiter.api.Assertions.assertEquals(
                true,
                form.getEnabled());
    }

    @Test
    void listConvertsNegativePageToZero() throws Exception {

        when(adminCustomerService.searchCustomers(
                any(AdminCustomerSearchForm.class),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(10)))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/customers")
                        .param("page", "-1")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isOk());

        verify(adminCustomerService)
                .searchCustomers(
                        any(AdminCustomerSearchForm.class),
                        org.mockito.ArgumentMatchers.eq(0),
                        org.mockito.ArgumentMatchers.eq(10));
    }

    @Test
    void listConvertsSizeBelowMinimumToOne() throws Exception {

        when(adminCustomerService.searchCustomers(
                any(AdminCustomerSearchForm.class),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/customers")
                        .param("size", "0")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isOk());

        verify(adminCustomerService)
                .searchCustomers(
                        any(AdminCustomerSearchForm.class),
                        org.mockito.ArgumentMatchers.eq(0),
                        org.mockito.ArgumentMatchers.eq(1));
    }

    @Test
    void listConvertsSizeAboveMaximumToOneHundred()
            throws Exception {

        when(adminCustomerService.searchCustomers(
                any(AdminCustomerSearchForm.class),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(100)))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/customers")
                        .param("size", "101")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isOk());

        verify(adminCustomerService)
                .searchCustomers(
                        any(AdminCustomerSearchForm.class),
                        org.mockito.ArgumentMatchers.eq(0),
                        org.mockito.ArgumentMatchers.eq(100));
    }
}
