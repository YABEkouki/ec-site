package com.example.ecsite.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.mail.MailSendException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.dto.AdminCustomerDetail;
import com.example.ecsite.dto.AdminCustomerListItem;
import com.example.ecsite.dto.AdminCustomerPurchaseSummary;
import com.example.ecsite.dto.AdminCustomerShippingAddress;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.CustomerNotFoundException;
import com.example.ecsite.form.AdminCustomerSearchForm;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.AdminCustomerService;
import com.example.ecsite.service.MailService;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.PasswordResetService;

@WebMvcTest(AdminCustomerController.class)
class AdminCustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminCustomerService adminCustomerService;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @MockitoBean
    private MailService mailService;

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
                        .param("hasOrders", "true")
                        .param("hasPurchases", "false")
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

        org.junit.jupiter.api.Assertions.assertEquals(
                true,
                form.getHasOrders());

        org.junit.jupiter.api.Assertions.assertEquals(
                false,
                form.getHasPurchases());
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
                "customer01@example.com",
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

        when(adminCustomerService.getPurchaseSummary(10L))
                .thenReturn(new AdminCustomerPurchaseSummary(
                        0L,
                        0L,
                        null));

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
                "customer01@example.com",
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
                "customer01@example.com",
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
                "customer01@example.com",
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

    @Test
    void detailAddsPurchaseSummaryToModel() throws Exception {
        Long userId = 1L;

        AdminCustomerDetail customer = new AdminCustomerDetail(
                userId,
                "user1",
                "user1@example.com",
                true,
                "山田 太郎",
                "1000001",
                "東京都",
                "千代田区",
                "丸の内1-1-1",
                "09012345678",
                List.of());

        AdminCustomerPurchaseSummary purchaseSummary = new AdminCustomerPurchaseSummary(
                4L,
                5000L,
                LocalDateTime.of(2026, 9, 4, 13, 0));

        when(adminCustomerService.findCustomerDetail(userId))
                .thenReturn(customer);
        when(adminCustomerService.getPurchaseSummary(userId))
                .thenReturn(purchaseSummary);

        mockMvc.perform(get("/admin/customers/{id}", userId)
                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/customers/detail"))
                .andExpect(model().attribute("customer", customer))
                .andExpect(model().attribute("purchaseSummary", purchaseSummary));
    }

    @Test
    void passwordResetConfirmationReturnsConfirmationView() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                "customer01@example.com",
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

        mockMvc.perform(
                get("/admin/customers/10/password-reset")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/customers/password-reset"))
                .andExpect(model().attribute("customer", customer));

        verify(adminCustomerService).findCustomerDetail(10L);
    }

    @Test
    void sendPasswordResetSendsMailAndRedirectsToCustomerDetail() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                "customer01@example.com",
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

        when(passwordResetService.issueToken("customer01@example.com"))
                .thenReturn("raw-token");

        mockMvc.perform(
                post("/admin/customers/10/password-reset")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/customers/10"))
                .andExpect(flash().attribute(
                        "message",
                        "パスワード再設定メールを送信しました。"));

        verify(passwordResetService)
                .issueToken("customer01@example.com");

        verify(mailService)
                .sendPasswordReset(
                        "customer01@example.com",
                        "raw-token");
    }

    @Test
    void sendPasswordResetDoesNotSendWhenEmailIsMissing() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                null,
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

        mockMvc.perform(
                post("/admin/customers/10/password-reset")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/customers/10"))
                .andExpect(flash().attribute(
                        "errorMessage",
                        "メールアドレスが登録されていないため、パスワード再設定メールを送信できません。"));

        verify(passwordResetService, never())
                .issueToken(any());

        verify(mailService, never())
                .sendPasswordReset(any(), any());
    }

    @Test
    void sendPasswordResetDoesNotSendWhenTokenCannotBeIssued() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                "customer01@example.com",
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

        when(passwordResetService.issueToken("customer01@example.com"))
                .thenReturn(null);

        mockMvc.perform(
                post("/admin/customers/10/password-reset")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/customers/10"))
                .andExpect(flash().attribute(
                        "errorMessage",
                        "パスワード再設定メールを送信できませんでした。しばらく待ってから再度お試しください。"));

        verify(mailService, never())
                .sendPasswordReset(any(), any());
    }

    @Test
    void sendPasswordResetInvalidatesTokenWhenMailSendingFails() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                "customer01@example.com",
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

        when(passwordResetService.issueToken("customer01@example.com"))
                .thenReturn("raw-token");

        org.mockito.Mockito.doThrow(new MailSendException("mail error"))
                .when(mailService)
                .sendPasswordReset(
                        "customer01@example.com",
                        "raw-token");

        mockMvc.perform(
                post("/admin/customers/10/password-reset")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/customers/10"))
                .andExpect(flash().attribute(
                        "errorMessage",
                        "パスワード再設定メールの送信に失敗しました。"));

        verify(passwordResetService)
                .invalidateToken("raw-token");
    }

    @Test
    void passwordResetConfirmationReturnsNotFoundWhenCustomerDoesNotExist()
            throws Exception {

        when(adminCustomerService.findCustomerDetail(999999L))
                .thenThrow(new CustomerNotFoundException(999999L));

        mockMvc.perform(
                get("/admin/customers/999999/password-reset")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void enabledConfirmationReturnsConfirmationView() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                "customer01@example.com",
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

        mockMvc.perform(
                get("/admin/customers/10/enabled")
                        .param("enabled", "false")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/customers/enabled"))
                .andExpect(model().attribute("customer", customer))
                .andExpect(model().attribute("enabled", false));

        verify(adminCustomerService).findCustomerDetail(10L);
    }

    @Test
    void changeEnabledDisablesCustomerAndRedirectsToDetail() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                "customer01@example.com",
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

        AdminUserDetails admin = new AdminUserDetails(
                100L,
                "admin01",
                "password",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        mockMvc.perform(
                post("/admin/customers/10/enabled")
                        .param("enabled", "false")
                        .with(user(admin))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/customers/10"))
                .andExpect(flash().attribute(
                        "message",
                        "ユーザーアカウントを無効にしました。"));

        verify(adminCustomerService).changeEnabled(
                10L,
                false,
                100L,
                "admin01");
    }

    @Test
    void changeEnabledDoesNotCallServiceWhenStateIsAlreadySame() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                "customer01@example.com",
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

        AdminUserDetails admin = new AdminUserDetails(
                100L,
                "admin01",
                "password",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        mockMvc.perform(
                post("/admin/customers/10/enabled")
                        .param("enabled", "true")
                        .with(user(admin))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/customers/10"))
                .andExpect(flash().attribute(
                        "message",
                        "このユーザーは既に有効です。"));

        verify(adminCustomerService, never())
                .changeEnabled(
                        10L,
                        true,
                        100L,
                        "admin01");
    }

    @Test
    void passwordResetConfirmationRedirectsForDisabledCustomer() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                "customer01@example.com",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of());

        when(adminCustomerService.findCustomerDetail(10L))
                .thenReturn(customer);

        mockMvc.perform(
                get("/admin/customers/10/password-reset")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/customers/10"));
    }

    @Test
    void sendPasswordResetDoesNotSendForDisabledCustomer() throws Exception {

        AdminCustomerDetail customer = new AdminCustomerDetail(
                10L,
                "customer01",
                "customer01@example.com",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of());

        when(adminCustomerService.findCustomerDetail(10L))
                .thenReturn(customer);

        mockMvc.perform(
                post("/admin/customers/10/password-reset")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/customers/10"))
                .andExpect(flash().attribute(
                        "errorMessage",
                        "無効なユーザーアカウントにはパスワード再設定メールを送信できません。"));

        verify(passwordResetService, never())
                .issueToken(any());

        verify(mailService, never())
                .sendPasswordReset(any(), any());
    }

}
