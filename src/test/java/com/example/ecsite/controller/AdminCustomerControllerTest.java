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

import com.example.ecsite.dto.AdminCustomerDetail;
import com.example.ecsite.dto.AdminCustomerListItem;
import com.example.ecsite.dto.AdminCustomerShippingAddress;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.CustomerNotFoundException;
import com.example.ecsite.form.AdminCustomerSearchForm;
import com.example.ecsite.service.AdminCustomerService;
import com.example.ecsite.service.OrderService;

@WebMvcTest(AdminCustomerController.class)
class AdminCustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminCustomerService adminCustomerService;

    @MockitoBean
    private OrderService orderService;

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

    @Test
    void detailReturnsCustomerDetailView() throws Exception {

        AdminCustomerShippingAddress shippingAddress = new AdminCustomerShippingAddress(
                "自宅",
                "山田太郎",
                "100-0001",
                "東京都",
                "千代田区",
                "千代田1-1",
                "090-1111-2222",
                true);

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                true,
                "山田太郎",
                "100-0001",
                "東京都",
                "千代田区",
                "千代田1-1",
                "090-1111-2222",
                List.of(shippingAddress));

        when(adminCustomerService.findCustomerDetail(10L))
                .thenReturn(customer);

        mockMvc.perform(
                get("/admin/customers/10")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(
                        view().name(
                                "admin/customers/detail"))
                .andExpect(
                        model().attribute(
                                "customer",
                                customer));

        verify(adminCustomerService)
                .findCustomerDetail(10L);
    }

    @Test
    void detailReturnsNotFoundWhenCustomerDoesNotExist() throws Exception {

        when(adminCustomerService.findCustomerDetail(999999L))
                .thenThrow(new CustomerNotFoundException(999999L));

        mockMvc.perform(
                get("/admin/customers/999999")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isNotFound());

        verify(adminCustomerService)
                .findCustomerDetail(999999L);
    }

    @Test
    void ordersReturnsCustomerOrderHistoryView() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of());

        Order order = new Order();

        Page<Order> orderPage = new PageImpl<>(List.of(order));

        when(adminCustomerService.findCustomerDetail(10L))
                .thenReturn(customer);

        when(orderService.findOrdersByUserId(10L, 0, 10))
                .thenReturn(orderPage);

        mockMvc.perform(
                get("/admin/customers/10/orders")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(
                        view().name(
                                "admin/customers/orders"))
                .andExpect(
                        model().attribute(
                                "customer",
                                customer))
                .andExpect(
                        model().attribute(
                                "orders",
                                hasSize(1)))
                .andExpect(
                        model().attribute(
                                "orderPage",
                                orderPage));

        verify(adminCustomerService)
                .findCustomerDetail(10L);

        verify(orderService)
                .findOrdersByUserId(10L, 0, 10);
    }

    @Test
    void ordersConvertsNegativePageToZero() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of());

        when(adminCustomerService.findCustomerDetail(10L))
                .thenReturn(customer);

        when(orderService.findOrdersByUserId(10L, 0, 10))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/customers/10/orders")
                        .param("page", "-1")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isOk());

        verify(orderService)
                .findOrdersByUserId(10L, 0, 10);
    }

    @Test
    void ordersConvertsSizeOutsideRange() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of());

        when(adminCustomerService.findCustomerDetail(10L))
                .thenReturn(customer);

        when(orderService.findOrdersByUserId(10L, 0, 1))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/customers/10/orders")
                        .param("size", "0")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isOk());

        verify(orderService)
                .findOrdersByUserId(10L, 0, 1);

        when(orderService.findOrdersByUserId(10L, 0, 100))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/customers/10/orders")
                        .param("size", "101")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isOk());

        verify(orderService)
                .findOrdersByUserId(10L, 0, 100);
    }

    @Test
    void ordersReturnsNotFoundWhenCustomerDoesNotExist() throws Exception {

        when(adminCustomerService.findCustomerDetail(999999L))
                .thenThrow(new CustomerNotFoundException(999999L));

        mockMvc.perform(
                get("/admin/customers/999999/orders")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isNotFound());

        verify(adminCustomerService)
                .findCustomerDetail(999999L);
    }

}
