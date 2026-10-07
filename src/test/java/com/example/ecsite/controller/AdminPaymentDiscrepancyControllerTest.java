package com.example.ecsite.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatusHistory;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.form.AdminPaymentDiscrepancySearchForm;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.repository.projection.AdminPaymentDiscrepancyListProjection;
import com.example.ecsite.service.AdminPaymentDiscrepancyService;

@WebMvcTest(AdminPaymentDiscrepancyController.class)
class AdminPaymentDiscrepancyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminPaymentDiscrepancyService service;

    @MockitoBean
    private AdminPaymentDiscrepancyListProjection discrepancy;

    @Test
    void listReturnsPaymentDiscrepancyListView() throws Exception {

        when(service.search(
                any(AdminPaymentDiscrepancySearchForm.class),
                eq(0),
                eq(10)))
                .thenReturn(
                        new PageImpl<>(
                                List.of(discrepancy)));

        mockMvc.perform(
                get("/admin/payment-discrepancies")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(
                        view().name(
                                "admin/payment-discrepancies/list"))
                .andExpect(
                        model().attributeExists(
                                "searchForm"))
                .andExpect(
                        model().attributeExists(
                                "page"))
                .andExpect(
                        model().attributeExists(
                                "paymentStatuses"))
                .andExpect(
                        model().attributeExists(
                                "paymentFlowStatuses"))
                .andExpect(
                        model().attribute(
                                "discrepancies",
                                hasSize(1)))
                .andExpect(
                        model().attributeExists(
                                "paymentDiscrepancyHandlingStatuses"));
    }

    @Test
    void listPassesSearchConditionsToService() throws Exception {

        when(service.search(
                any(AdminPaymentDiscrepancySearchForm.class),
                eq(0),
                eq(10)))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/payment-discrepancies")
                        .param("orderId", "123")
                        .param("userId", "456")
                        .param(
                                "localStatus",
                                PaymentStatus.PENDING.name())
                        .param(
                                "providerStatus",
                                PaymentFlowStatus.REQUIRES_CAPTURE.name())
                        .param(
                                "handlingStatus",
                                PaymentDiscrepancyHandlingStatus.IN_PROGRESS.name())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        ArgumentCaptor<AdminPaymentDiscrepancySearchForm> formCaptor = ArgumentCaptor.forClass(
                AdminPaymentDiscrepancySearchForm.class);

        verify(service).search(
                formCaptor.capture(),
                eq(0),
                eq(10));

        AdminPaymentDiscrepancySearchForm form = formCaptor.getValue();

        assertEquals(123L, form.getOrderId());
        assertEquals(456L, form.getUserId());
        assertEquals(
                PaymentStatus.PENDING,
                form.getLocalStatus());
        assertEquals(
                PaymentFlowStatus.REQUIRES_CAPTURE,
                form.getProviderStatus());
        assertEquals(
                PaymentDiscrepancyHandlingStatus.IN_PROGRESS,
                form.getHandlingStatus());
    }

    @Test
    void listConvertsNegativePageToZero() throws Exception {

        when(service.search(
                any(AdminPaymentDiscrepancySearchForm.class),
                eq(0),
                eq(10)))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/payment-discrepancies")
                        .param("page", "-1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(service).search(
                any(AdminPaymentDiscrepancySearchForm.class),
                eq(0),
                eq(10));
    }

    @Test
    void listConvertsSizeBelowMinimumToOne() throws Exception {

        when(service.search(
                any(AdminPaymentDiscrepancySearchForm.class),
                eq(0),
                eq(1)))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/payment-discrepancies")
                        .param("size", "0")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(service).search(
                any(AdminPaymentDiscrepancySearchForm.class),
                eq(0),
                eq(1));
    }

    @Test
    void listConvertsSizeAboveMaximumToOneHundred()
            throws Exception {

        when(service.search(
                any(AdminPaymentDiscrepancySearchForm.class),
                eq(0),
                eq(100)))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/payment-discrepancies")
                        .param("size", "101")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(service).search(
                any(AdminPaymentDiscrepancySearchForm.class),
                eq(0),
                eq(100));
    }

    @Test
    void listAddsReturnUrlPreservingSearchConditionsAndPaging()
            throws Exception {

        when(service.search(
                any(AdminPaymentDiscrepancySearchForm.class),
                eq(2),
                eq(20)))
                .thenReturn(Page.empty());

        mockMvc.perform(
                get("/admin/payment-discrepancies")
                        .param("orderId", "123")
                        .param("userId", "456")
                        .param(
                                "localStatus",
                                PaymentStatus.PENDING.name())
                        .param(
                                "providerStatus",
                                PaymentFlowStatus.REQUIRES_CAPTURE.name())
                        .param(
                                "handlingStatus",
                                PaymentDiscrepancyHandlingStatus.IN_PROGRESS.name())
                        .param("page", "2")
                        .param("size", "20")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(
                        model().attribute(
                                "returnUrl",
                                "/admin/payment-discrepancies"
                                        + "?orderId=123"
                                        + "&userId=456"
                                        + "&localStatus=PENDING"
                                        + "&providerStatus=REQUIRES_CAPTURE"
                                        + "&handlingStatus=IN_PROGRESS"
                                        + "&page=2"
                                        + "&size=20"));
    }

    @Test
    void detailReturnsPaymentDiscrepancyDetailView() throws Exception {

        Payment payment = mock(Payment.class);
        Order order = mock(Order.class);

        when(payment.getOrder())
                .thenReturn(order);

        when(order.getId())
                .thenReturn(123L);

        PaymentDiscrepancy paymentDiscrepancy = new PaymentDiscrepancy(
                payment,
                PaymentStatus.PENDING,
                PaymentFlowStatus.REQUIRES_CAPTURE,
                LocalDateTime.of(2026, 10, 7, 10, 0));

        List<PaymentDiscrepancyHandlingStatusHistory> histories = List.of();

        when(service.findById(10L))
                .thenReturn(paymentDiscrepancy);

        when(service.findHandlingStatusHistories(10L))
                .thenReturn(histories);

        mockMvc.perform(
                get("/admin/payment-discrepancies/10")
                        .param(
                                "returnUrl",
                                "/admin/payment-discrepancies"
                                        + "?handlingStatus=IN_PROGRESS")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(
                        view().name(
                                "admin/payment-discrepancies/detail"))
                .andExpect(
                        model().attribute(
                                "discrepancy",
                                paymentDiscrepancy))
                .andExpect(
                        model().attributeExists(
                                "handlingStatusForm"))
                .andExpect(
                        model().attributeExists(
                                "handlingStatuses"))
                .andExpect(
                        model().attribute(
                                "handlingStatusHistories",
                                histories))
                .andExpect(
                        model().attribute(
                                "returnUrl",
                                "/admin/payment-discrepancies"
                                        + "?handlingStatus=IN_PROGRESS"));
    }

    @Test
    void changeHandlingStatusChangesStatusAndRedirectsToDetail()
            throws Exception {

        when(service.changeHandlingStatus(
                10L,
                PaymentDiscrepancyHandlingStatus.IN_PROGRESS,
                100L,
                "admin"))
                .thenReturn(true);

        mockMvc.perform(
                post("/admin/payment-discrepancies/10/handling-status")
                        .param(
                                "handlingStatus",
                                PaymentDiscrepancyHandlingStatus.IN_PROGRESS.name())
                        .param(
                                "returnUrl",
                                "/admin/payment-discrepancies"
                                        + "?handlingStatus=UNCONFIRMED")
                        .with(csrf())
                        .with(user(
                                new com.example.ecsite.security.AdminUserDetails(
                                        100L,
                                        "admin",
                                        "password",
                                        true,
                                        List.of()))))
                .andExpect(status().is3xxRedirection())
                .andExpect(
                        flash().attribute(
                                "successMessage",
                                "管理者対応状態を変更しました。"))
                .andExpect(
                        redirectedUrl(
                                "/admin/payment-discrepancies/10"
                                        + "?returnUrl=%2Fadmin%2Fpayment-discrepancies"
                                        + "%3FhandlingStatus%3DUNCONFIRMED"));

        verify(service).changeHandlingStatus(
                10L,
                PaymentDiscrepancyHandlingStatus.IN_PROGRESS,
                100L,
                "admin");
    }

    @Test
    void changeHandlingStatusShowsUnchangedMessageWhenStatusIsSame()
            throws Exception {

        when(service.changeHandlingStatus(
                10L,
                PaymentDiscrepancyHandlingStatus.UNCONFIRMED,
                100L,
                "admin"))
                .thenReturn(false);

        mockMvc.perform(
                post("/admin/payment-discrepancies/10/handling-status")
                        .param(
                                "handlingStatus",
                                PaymentDiscrepancyHandlingStatus.UNCONFIRMED.name())
                        .with(csrf())
                        .with(user(
                                new com.example.ecsite.security.AdminUserDetails(
                                        100L,
                                        "admin",
                                        "password",
                                        true,
                                        List.of()))))
                .andExpect(status().is3xxRedirection())
                .andExpect(
                        flash().attribute(
                                "successMessage",
                                "管理者対応状態は変更されていません。"))
                .andExpect(
                        redirectedUrl(
                                "/admin/payment-discrepancies/10"
                                        + "?returnUrl=%2Fadmin%2Fpayment-discrepancies"));
    }

    @Test
    void detailFallsBackWhenReturnUrlIsUnsafe() throws Exception {

        Payment payment = mock(Payment.class);
        Order order = mock(Order.class);

        when(payment.getOrder())
                .thenReturn(order);

        when(order.getId())
                .thenReturn(123L);

        PaymentDiscrepancy paymentDiscrepancy = new PaymentDiscrepancy(
                payment,
                PaymentStatus.PENDING,
                PaymentFlowStatus.REQUIRES_CAPTURE,
                LocalDateTime.of(2026, 10, 7, 10, 0));

        when(service.findById(10L))
                .thenReturn(paymentDiscrepancy);

        when(service.findHandlingStatusHistories(10L))
                .thenReturn(List.of());

        mockMvc.perform(
                get("/admin/payment-discrepancies/10")
                        .param(
                                "returnUrl",
                                "https://example.com/evil")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(
                        model().attribute(
                                "returnUrl",
                                "/admin/payment-discrepancies"));
    }

}
