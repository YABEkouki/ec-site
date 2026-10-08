package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.ecsite.entity.Order;
import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatusHistory;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.form.AdminPaymentDiscrepancySearchForm;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.repository.projection.AdminPaymentDiscrepancyListProjection;
import com.example.ecsite.service.AdminPaymentDiscrepancyService;
import com.example.ecsite.service.AdminPaymentDiscrepancyReconciliationService;
import com.example.ecsite.service.AdminUserDetailsService;
import com.example.ecsite.service.CustomUserDetailsService;
import com.example.ecsite.security.AdminAuthenticationSuccessHandler;
import com.example.ecsite.security.CustomerAuthenticationSuccessHandler;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditResult;

@WebMvcTest(AdminPaymentDiscrepancyController.class)
@Import(SecurityConfig.class)
class AdminPaymentDiscrepancyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminPaymentDiscrepancyService service;

    @MockitoBean
    private AdminPaymentDiscrepancyReconciliationService reconciliationService;

    @MockitoBean
    private AdminUserDetailsService adminUserDetailsService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private AdminAuthenticationSuccessHandler adminAuthenticationSuccessHandler;

    @MockitoBean
    private CustomerAuthenticationSuccessHandler customerAuthenticationSuccessHandler;

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

        org.springframework.test.util.ReflectionTestUtils.setField(paymentDiscrepancy, "version", 7L);

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
                .andExpect(model().attribute("handlingStatusForm", org.hamcrest.Matchers.hasProperty(
                        "expectedVersion", org.hamcrest.Matchers.is(7L))))
                .andExpect(result -> assertThat(Pattern.compile(
                        "<input\\b(?=[^>]*type=\"hidden\")(?=[^>]*name=\"expectedVersion\")(?=[^>]*value=\"7\")[^>]*>")
                        .matcher(result.getResponse().getContentAsString()).find()).isTrue())
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
                0L,
                PaymentDiscrepancyHandlingStatus.IN_PROGRESS,
                100L,
                "admin"))
                .thenReturn(true);

        mockMvc.perform(
                post("/admin/payment-discrepancies/10/handling-status")
                        .param("expectedVersion", "0")
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
                                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))))
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
                0L,
                PaymentDiscrepancyHandlingStatus.IN_PROGRESS,
                100L,
                "admin");
    }

    @Test
    void changeHandlingStatusShowsUnchangedMessageWhenStatusIsSame()
            throws Exception {

        when(service.changeHandlingStatus(
                10L,
                0L,
                PaymentDiscrepancyHandlingStatus.UNCONFIRMED,
                100L,
                "admin"))
                .thenReturn(false);

        mockMvc.perform(
                post("/admin/payment-discrepancies/10/handling-status")
                        .param("expectedVersion", "0")
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
                                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))))
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

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "invalid", "9223372036854775808"})
    void rejectsMissingOrMalformedExpectedVersion(String version) throws Exception {
        var request = post("/admin/payment-discrepancies/10/handling-status")
                .param("handlingStatus", "CONFIRMED").with(csrf()).with(user(adminPrincipal()));
        if (!version.isEmpty()) request.param("expectedVersion", version);
        mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payment-discrepancies/10?returnUrl=%2Fadmin%2Fpayment-discrepancies"))
                .andExpect(flash().attribute("errorMessage",
                        "入力内容が不正です。最新の状態を確認して、もう一度操作してください。"))
                .andExpect(result -> assertThat(result.getFlashMap().keySet()).doesNotContain("successMessage", "handlingStatusForm"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "INVALID"})
    void rejectsMissingOrInvalidHandlingStatus(String handling) throws Exception {
        var request = post("/admin/payment-discrepancies/10/handling-status")
                .param("expectedVersion", "0").with(csrf()).with(user(adminPrincipal()));
        if (!handling.isEmpty()) request.param("handlingStatus", handling);
        mockMvc.perform(request).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage",
                        "入力内容が不正です。最新の状態を確認して、もう一度操作してください。"))
                .andExpect(result -> assertThat(result.getFlashMap().keySet()).doesNotContain("successMessage"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void notifiesBothVersionMismatchAndCommitConflictAndReloadsLatestForm(boolean commitConflict) throws Exception {
        RuntimeException conflict = commitConflict
                ? new org.springframework.orm.ObjectOptimisticLockingFailureException(PaymentDiscrepancy.class, 10L)
                : new com.example.ecsite.service.PaymentDiscrepancyConflictException(10L);
        when(service.changeHandlingStatus(10L, 7L, PaymentDiscrepancyHandlingStatus.CONFIRMED, 100L, "admin"))
                .thenThrow(conflict);
        var result = mockMvc.perform(post("/admin/payment-discrepancies/10/handling-status")
                        .param("expectedVersion", "7").param("handlingStatus", "CONFIRMED")
                        .param("returnUrl", "/admin/payment-discrepancies?handlingStatus=UNCONFIRMED")
                        .with(csrf()).with(user(adminPrincipal())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payment-discrepancies/10?returnUrl=%2Fadmin%2Fpayment-discrepancies%3FhandlingStatus%3DUNCONFIRMED"))
                .andExpect(flash().attribute("errorMessage",
                        "対象データが更新されたため、変更できませんでした。最新の状態を確認して、もう一度操作してください。"))
                .andExpect(response -> assertThat(response.getFlashMap().keySet()).doesNotContain("successMessage", "handlingStatusForm"))
                .andReturn();
        verify(service, org.mockito.Mockito.times(1)).changeHandlingStatus(10L, 7L,
                PaymentDiscrepancyHandlingStatus.CONFIRMED, 100L, "admin");

        Payment payment = mock(Payment.class);
        Order order = mock(Order.class);
        when(payment.getOrder()).thenReturn(order);
        when(order.getId()).thenReturn(123L);
        PaymentDiscrepancy latest = new PaymentDiscrepancy(payment, PaymentStatus.PENDING,
                PaymentFlowStatus.REQUIRES_CAPTURE, LocalDateTime.of(2026, 10, 7, 10, 0));
        latest.changeHandlingStatus(PaymentDiscrepancyHandlingStatus.IN_PROGRESS, LocalDateTime.of(2026, 10, 7, 11, 0));
        org.springframework.test.util.ReflectionTestUtils.setField(latest, "version", 8L);
        when(service.findById(10L)).thenReturn(latest);
        when(service.findHandlingStatusHistories(10L)).thenReturn(List.of());
        mockMvc.perform(get(result.getResponse().getRedirectedUrl()).flashAttrs(result.getFlashMap())
                        .with(user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("handlingStatusForm", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.hasProperty("expectedVersion", org.hamcrest.Matchers.is(8L)),
                        org.hamcrest.Matchers.hasProperty("handlingStatus", org.hamcrest.Matchers.is(PaymentDiscrepancyHandlingStatus.IN_PROGRESS)))))
                .andExpect(content().string(containsString("対象データが更新されたため、変更できませんでした。")))
                .andExpect(content().string(not(containsString("管理者対応状態を変更しました。"))));
    }

    private com.example.ecsite.security.AdminUserDetails adminPrincipal() {
        return new com.example.ecsite.security.AdminUserDetails(100L, "admin", "password", true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
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

    @ParameterizedTest
    @CsvSource({
            "CONSISTENT, REQUIRES_CAPTURE, PAY.JPの最新状態との整合を確認しました。管理者対応状態は変更していません。",
            "INCONSISTENT, SUCCEEDED, PAY.JPの最新状態を確認しましたが、不整合が継続しています。",
            "IN_PROGRESS, PROCESSING, PAY.JPの決済処理が進行中のため、判定を保留しました。"
    })
    void recheckReturnsResultMessageAndPreservesReturnUrl(
            PaymentDiscrepancyAuditResult.Status auditStatus,
            PaymentFlowStatus providerStatus,
            String message) throws Exception {
        PaymentDiscrepancyAuditResult result = new PaymentDiscrepancyAuditResult(
                auditStatus, null, providerStatus,
                auditStatus == PaymentDiscrepancyAuditResult.Status.IN_PROGRESS ? List.of() : List.of(10L));
        when(reconciliationService.reconcile(10L)).thenReturn(result);

        mockMvc.perform(post("/admin/payment-discrepancies/10/recheck")
                        .param("returnUrl", "/admin/payment-discrepancies?handlingStatus=IN_PROGRESS&page=2")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("recheckMessage", message))
                .andExpect(flash().attribute("recheckResult", result))
                .andExpect(redirectedUrl("/admin/payment-discrepancies/10"
                        + "?returnUrl=%2Fadmin%2Fpayment-discrepancies%3FhandlingStatus%3DIN_PROGRESS%26page%3D2"));

        verify(reconciliationService).reconcile(10L);
        verify(service).findById(10L);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({
            "PAYMENT_NOT_FOUND, 対象の決済が見つからないため、再照合できませんでした。",
            "UNSUPPORTED_PAYMENT, PAY.JPのカード決済ではないため、再照合できませんでした。",
            "MISSING_PROVIDER_PAYMENT_ID, PAY.JP決済IDが未設定のため、再照合できませんでした。",
            "PENDING_TRANSACTION, 処理中の決済取引があるため、再照合できませんでした。"
    })
    void recheckShowsSkipReason(PaymentDiscrepancyAuditResult.SkipReason reason, String message)
            throws Exception {
        PaymentDiscrepancyAuditResult result = new PaymentDiscrepancyAuditResult(
                PaymentDiscrepancyAuditResult.Status.SKIPPED, reason, null, List.of());
        when(reconciliationService.reconcile(10L)).thenReturn(result);

        mockMvc.perform(post("/admin/payment-discrepancies/10/recheck")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("recheckMessage", message))
                .andExpect(flash().attribute("recheckResult", result))
                .andExpect(redirectedUrl("/admin/payment-discrepancies/10"
                        + "?returnUrl=%2Fadmin%2Fpayment-discrepancies"));
        verify(service).findById(10L);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recheckHidesExceptionDetails(boolean apiFailure) throws Exception {
        PaymentDiscrepancy record = detailRecord();
        when(service.findById(10L)).thenReturn(record);
        RuntimeException failure = apiFailure
                ? new PaymentGatewayException("secret-provider-detail")
                : new IllegalArgumentException("internal-record-detail");
        when(reconciliationService.reconcile(10L)).thenThrow(failure);

        mockMvc.perform(post("/admin/payment-discrepancies/10/recheck")
                        .param("returnUrl", "/admin/payment-discrepancies?page=2")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", "再照合できませんでした。時間をおいて再度お試しください。"))
                .andExpect(flash().attributeCount(1))
                .andExpect(redirectedUrl("/admin/payment-discrepancies/10"
                        + "?returnUrl=%2Fadmin%2Fpayment-discrepancies%3Fpage%3D2"));
        verify(service).findById(10L);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({
            "/admin/payment-discrepancies?page=2, /admin/payment-discrepancies?page=2",
            "https://example.com/evil, /admin/payment-discrepancies",
            "//example.com/evil, /admin/payment-discrepancies"
    })
    void missingDiscrepancyReturnsToSafeListAndDisplaysError(String returnUrl, String expectedUrl)
            throws Exception {
        when(service.findById(999L)).thenThrow(new IllegalArgumentException("internal-record-detail"));

        var response = mockMvc.perform(post("/admin/payment-discrepancies/999/recheck")
                        .param("returnUrl", returnUrl).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedUrl))
                .andExpect(flash().attribute("errorMessage", "対象の決済不整合が見つからないため、再照合できませんでした。"))
                .andExpect(flash().attributeCount(1))
                .andReturn();
        verifyNoInteractions(reconciliationService);
        when(service.search(any(AdminPaymentDiscrepancySearchForm.class), any(Integer.class), any(Integer.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get(expectedUrl).flashAttrs(response.getFlashMap())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("対象の決済不整合が見つからないため、再照合できませんでした。")))
                .andExpect(content().string(not(containsString("internal-record-detail"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/evil", "//example.com/evil", "/admin/orders", "/admin/payment-discrepancies/10", "/admin/payment-discrepancies-evil"})
    void recheckRejectsUnsafeReturnUrl(String returnUrl) throws Exception {
        when(reconciliationService.reconcile(10L)).thenReturn(new PaymentDiscrepancyAuditResult(
                PaymentDiscrepancyAuditResult.Status.CONSISTENT, null, PaymentFlowStatus.REQUIRES_CAPTURE, List.of()));

        mockMvc.perform(post("/admin/payment-discrepancies/10/recheck")
                        .param("returnUrl", returnUrl).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payment-discrepancies/10"
                        + "?returnUrl=%2Fadmin%2Fpayment-discrepancies"));
    }

    @Test
    void recheckRequiresCsrf() throws Exception {
        mockMvc.perform(post("/admin/payment-discrepancies/10/recheck")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reconciliationService, service);
    }

    @Test
    void recheckRejectsNonAdmin() throws Exception {
        mockMvc.perform(post("/admin/payment-discrepancies/10/recheck")
                        .with(user("customer").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reconciliationService, service);
    }

    @Test
    void recheckRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/admin/payment-discrepancies/10/recheck").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
        verifyNoInteractions(reconciliationService, service);
    }

    @Test
    void detailRendersRecheckResultSeparatelyAndIncludesPostFormWithCsrf() throws Exception {
        PaymentDiscrepancy record = detailRecord();
        when(service.findById(10L)).thenReturn(record);
        when(service.findHandlingStatusHistories(10L)).thenReturn(List.of());
        PaymentDiscrepancyAuditResult result = new PaymentDiscrepancyAuditResult(
                PaymentDiscrepancyAuditResult.Status.CONSISTENT, null, PaymentFlowStatus.REQUIRES_CAPTURE, List.of(10L, 11L));

        String html = mockMvc.perform(get("/admin/payment-discrepancies/10")
                        .param("returnUrl", "/admin/payment-discrepancies?page=2")
                        .flashAttr("recheckResult", result)
                        .flashAttr("recheckMessage", "整合を確認しました。")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("検知時のPAY.JP状態")))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(
                "<dd id=\"recheck-provider-status\">REQUIRES_CAPTURE</dd>",
                "<dd id=\"recheck-status\">CONSISTENT</dd>");
        String relatedRecords = renderedElement(html, "dd", "recheck-discrepancies");
        assertThat(relatedRecords).contains(
                "href=\"/admin/payment-discrepancies/10?returnUrl=/admin/payment-discrepancies?page%3D2\"",
                "href=\"/admin/payment-discrepancies/11?returnUrl=/admin/payment-discrepancies?page%3D2\"");
        assertThat(relatedRecords).contains("、");
        String form = renderedElement(html, "form", "payment-recheck-form");
        assertThat(form).contains(
                "method=\"post\"", "action=\"/admin/payment-discrepancies/10/recheck\"",
                "name=\"_csrf\"", "name=\"returnUrl\" value=\"/admin/payment-discrepancies?page=2\"");
        verifyNoInteractions(reconciliationService);
    }

    @Test
    void detailRendersSkippedResultWithoutPretendingProviderWasRetrieved() throws Exception {
        PaymentDiscrepancy record = detailRecord();
        when(service.findById(10L)).thenReturn(record);
        when(service.findHandlingStatusHistories(10L)).thenReturn(List.of());

        String html = mockMvc.perform(get("/admin/payment-discrepancies/10")
                        .flashAttr("recheckResult", new PaymentDiscrepancyAuditResult(
                                PaymentDiscrepancyAuditResult.Status.SKIPPED,
                                PaymentDiscrepancyAuditResult.SkipReason.PENDING_TRANSACTION, null, List.of()))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("secret-provider-detail"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<dd id=\"recheck-provider-status\">未取得</dd>");
        assertThat(renderedElement(html, "dd", "recheck-discrepancies")).contains("なし").doesNotContain("<a ");
    }

    private String renderedElement(String html, String tag, String id) {
        Matcher matcher = Pattern.compile("(?s)<" + tag + " id=\"" + id + "\".*?</" + tag + ">")
                .matcher(html);
        assertThat(matcher.find()).as("rendered element %s", id).isTrue();
        return matcher.group();
    }

    private PaymentDiscrepancy detailRecord() {
        Order order = mock(Order.class);
        Payment payment = mock(Payment.class);
        when(order.getId()).thenReturn(123L);
        when(payment.getOrder()).thenReturn(order);
        PaymentDiscrepancy record = new PaymentDiscrepancy(payment, PaymentStatus.AUTHORIZED,
                PaymentFlowStatus.SUCCEEDED, LocalDateTime.of(2026, 10, 7, 10, 0));
        org.springframework.test.util.ReflectionTestUtils.setField(record, "id", 10L);
        return record;
    }
}
