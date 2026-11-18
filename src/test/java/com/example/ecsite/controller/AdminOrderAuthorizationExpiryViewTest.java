package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.example.ecsite.entity.Order;
import com.example.ecsite.form.AdminAuthorizationExpiryFilter;
import com.example.ecsite.form.AdminOrderSearchForm;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.*;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.payment.AdminAuthorizationExpiryQueries.*;

/** Feature135-A2: the authorization expiry is rendered on the administrator order list and detail. */
class AdminOrderAuthorizationExpiryViewTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 16, 14, 0);
    private final OrderService orders = mock(OrderService.class);
    private final OrderPaymentViewReadService paymentViewReader = mock(OrderPaymentViewReadService.class);
    private final AdminAuthorizationExpiryQueries expiries = mock(AdminAuthorizationExpiryQueries.class);
    private final Order order = new Order(1L, 100, DEADLINE.withHour(10), DEADLINE);

    private MockMvc mvc() {
        ReflectionTestUtils.setField(order, "id", 42L);
        when(orders.findOrderWithItems(42L)).thenReturn(order);
        when(paymentViewReader.read(order)).thenReturn(OrderPaymentViewFactory.unavailable());
        var factory = new OrderShippingPreparationViewFactory(new OrderModificationPolicy(),
                Clock.fixed(DEADLINE.plusDays(1).atZone(ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo")));
        var controller = new AdminOrderController(orders, mock(OrderCsvService.class),
                mock(OrderStatusHistoryService.class), mock(OrderNoteService.class),
                mock(OrderHandlingStatusHistoryService.class), mock(OrderShippingAddressHistoryService.class),
                mock(OrderAssigneeHistoryService.class), mock(OrderContentChangeHistoryService.class),
                mock(AdminAccountService.class), mock(PaymentService.class), mock(PaymentCaptureService.class),
                mock(AdminPaymentCancellationService.class), factory, paymentViewReader,
                mock(com.example.ecsite.service.orderchange.AdminOldCancelRecoveryViewService.class),
                mock(com.example.ecsite.service.orderchange.AdminOrderHoldViewService.class));
        ReflectionTestUtils.invokeMethod(controller, "setAuthorizationExpiries", expiries);
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var views = new ThymeleafViewResolver(); views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8"); views.setContentType("text/html;charset=UTF-8");
        var mvc = MockMvcBuilders.standaloneSetup(controller).setViewResolvers(views)
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository())).build();
        ReflectionTestUtils.invokeMethod(mvc.getDispatcherServlet().getWebApplicationContext(),
                "addBean", "requestDataValueProcessor", new CsrfRequestDataValueProcessor());
        return mvc;
    }

    private static Badge badge(Level level, long hours, boolean estimated) {
        return new Badge(level, LocalDateTime.of(2026, 11, 20, 10, 30), estimated, hours);
    }

    @Test void detailShowsEachAuthorizationExpiryAndOpenDiscrepancies() throws Exception {
        var mvc = mvc();
        when(expiries.forOrder(42L)).thenReturn(new OrderExpiry(
                List.of(new PaymentExpiry(7, true, badge(Level.EXPIRED, 0, false)),
                        new PaymentExpiry(8, false, badge(Level.VALID, 100, true))),
                List.of(new Discrepancy(16, 7, "AUTHORIZED", "CANCELED", LocalDateTime.of(2026, 11, 17, 11, 37), "UNCONFIRMED"))));
        var html = mvc.perform(get("/admin/orders/42")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("カード与信期限", "与信期限切れ", "与信期限まで残り4日", "2026/11/20 10:30",
                "PAY.JP", "推定（作成時刻＋5日）", "有効な決済ではありません", "発送準備・倉庫出力・発送（売上確定）ができません",
                "未解決の決済不整合", "/admin/payment-discrepancies/16", "与信期限切れによる自動取消");
        assertThat(html).contains("expiry-expired", "expiry-valid");
    }

    @Test void detailWithoutAuthorizationsShowsNoExpiryRows() throws Exception {
        var mvc = mvc();
        when(expiries.forOrder(42L)).thenReturn(new OrderExpiry(List.of(), List.of()));
        var html = mvc.perform(get("/admin/orders/42")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("カード与信期限", "未解決の決済不整合");
    }

    @Test void listShowsBadgesAndKeepsTheFilterInLinks() throws Exception {
        var mvc = mvc();
        var admin = new AdminUserDetails(5L, "admin", "password", true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, "password", admin.getAuthorities()));
        try {
            when(orders.searchOrders(any(AdminOrderSearchForm.class), eq(5L), eq(0), eq(10)))
                    .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 10), 1));
            when(expiries.badges(List.of(42L))).thenReturn(Map.of(42L, badge(Level.SOON, 5, true)));
            var html = mvc.perform(get("/admin/orders").param("authorizationExpiry", "ATTENTION")
                    .principal(SecurityContextHolder.getContext().getAuthentication()))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(html).contains("カード与信期限", "期限切れ・24時間未満", "与信期限まで5時間", "expiry-soon", "推定",
                    "authorizationExpiry=ATTENTION");
            assertThat(html).containsPattern("<option value=\"ATTENTION\" selected=\"selected\"");
            verify(orders).searchOrders(argThat(f -> f.getAuthorizationExpiry() == AdminAuthorizationExpiryFilter.ATTENTION),
                    eq(5L), eq(0), eq(10));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
