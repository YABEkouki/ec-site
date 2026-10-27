package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.*;
import java.nio.charset.StandardCharsets;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.service.*;
import com.example.ecsite.service.payment.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class AdminOrderShippingPreparationViewTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 16, 14, 0);
    private final OrderService orders = mock(OrderService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final OrderPaymentViewReadService paymentViewReader = mock(OrderPaymentViewReadService.class);
    private final PaymentCaptureService captures = mock(PaymentCaptureService.class);
    private final AdminPaymentCancellationService cancellations = mock(AdminPaymentCancellationService.class);
    private final Order order = new Order(1L, 100, DEADLINE.withHour(10), DEADLINE);
    private final String returnUrl = "/admin/orders?page=2&size=20&sort=id,desc";

    private MockMvc mvc(LocalDateTime now) {
        ReflectionTestUtils.setField(order, "id", 42L);
        when(orders.findOrderWithItems(42L)).thenReturn(order);
        var factory = new OrderShippingPreparationViewFactory(new OrderModificationPolicy(),
                Clock.fixed(now.atZone(ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo")));
        when(paymentViewReader.read(order)).thenReturn(OrderPaymentViewFactory.unavailable());
        var controller = new AdminOrderController(orders, mock(OrderCsvService.class),
                mock(OrderStatusHistoryService.class), mock(OrderNoteService.class),
                mock(OrderHandlingStatusHistoryService.class), mock(OrderShippingAddressHistoryService.class),
                mock(OrderAssigneeHistoryService.class), mock(OrderContentChangeHistoryService.class),
                mock(AdminAccountService.class), payments, captures, cancellations, factory, paymentViewReader, mock(com.example.ecsite.service.orderchange.AdminOldCancelRecoveryViewService.class));
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var views = new ThymeleafViewResolver(); views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8"); views.setContentType("text/html;charset=UTF-8");
        var mvc = MockMvcBuilders.standaloneSetup(controller).setViewResolvers(views)
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository())).build();
        ReflectionTestUtils.invokeMethod(mvc.getDispatcherServlet().getWebApplicationContext(),
                "addBean", "requestDataValueProcessor", new CsrfRequestDataValueProcessor());
        return mvc;
    }

    private String render(LocalDateTime now) throws Exception {
        OrderStatus originalStatus = order.getStatus();
        LocalDateTime originalStartedAt = order.getShippingPreparationStartedAt();
        Long originalActor = order.getShippingPreparationStartedByAccountId();
        String originalUsername = order.getShippingPreparationStartedByUsername();
        var result = mvc(now).perform(get("/admin/orders/42").param("returnUrl", returnUrl)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var model = result.getModelAndView().getModel();
        assertThat(model).containsKeys("order", "shippingPreparationView", "canCaptureForShipment",
                "statusHistories", "orderNotes", "orderNoteForm", "handlingStatuses", "handlingStatusHistories",
                "shippingAddressHistories", "assigneeHistories", "handlingStatusForm", "assignableAdmins");
        assertThat(model.get("returnUrl")).isEqualTo(returnUrl);
        assertThat(model.get("order")).isSameAs(order);
        verify(orders).findOrderWithItems(42L);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(captures, cancellations);
        verify(paymentViewReader).read(order);
        verifyNoInteractions(payments);
        assertThat(order.getStatus()).isEqualTo(originalStatus);
        assertThat(order.getShippingPreparationStartedAt()).isEqualTo(originalStartedAt);
        assertThat(order.getShippingPreparationStartedByAccountId()).isEqualTo(originalActor);
        assertThat(order.getShippingPreparationStartedByUsername()).isEqualTo(originalUsername);
        assertThat(order.getContentRevision()).isZero();
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (((OrderShippingPreparationViewFactory.View) model.get("shippingPreparationView")).canStart()) {
            CsrfToken token = (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
            assertThat(token).isNotNull();
            assertThat(token.getToken()).isNotBlank();
            String section = html.substring(html.indexOf("<section id=\"shipping-preparation\""), html.indexOf("<h2>注文操作</h2>"));
            assertThat(section).contains("value=\"" + token.getToken() + "\"");
        }
        return html;
    }

    @Test
    void candidateFormUsesRealTemplateAndCsrfEvenWithoutCaptureEligibility() throws Exception {
        // canCaptureForShipment defaults false: it must not gate the preparation button.
        String html = render(DEADLINE);
        String section = html.substring(html.indexOf("<section id=\"shipping-preparation\""), html.indexOf("<h2>注文操作</h2>"));
        assertThat(section).contains("発送準備", "発送準備を開始する", "受付時に", "最終判定",
                "action=\"/admin/orders/42/start-shipping-preparation\"", "method=\"post\"",
                "name=\"returnUrl\"", "page=2&amp;size=20&amp;sort=id,desc", "name=\"_csrf\"");
        assertThat(section).doesNotContain("name=\"actorId\"", "name=\"username\"", "name=\"paymentId\"", "textarea");
        assertThat(order.getShippingPreparationStartedAt()).isNull();
    }

    @Test
    void startedSnapshotIsEscapedAndButtonHidden() throws Exception {
        order.beginShippingPreparation(DEADLINE, 7L, "<script>alert(1)</script>");
        String html = render(DEADLINE);
        assertThat(html).contains("発送準備開始済み", "2026/10/16 14:00", "&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>alert(1)</script>", "発送準備を開始する");
        assertThat(html).contains("開始管理者ID", ">7</");
    }

    @Test
    void beforeDeadlineHidesButton() throws Exception {
        assertThat(render(DEADLINE.minusNanos(1))).contains("変更期限前").doesNotContain("発送準備を開始する");
    }

    @Test
    void inconsistentMetadataWarnsAndHidesButton() throws Exception {
        ReflectionTestUtils.setField(order, "shippingPreparationStartedByAccountId", 7L);
        assertThat(render(DEADLINE)).contains("発送準備情報に不整合").doesNotContain("発送準備を開始する");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "CANCELLED"})
    void terminalOrderHidesButton(String status) throws Exception {
        ReflectionTestUtils.setField(order, "status", OrderStatus.valueOf(status));
        assertThat(render(DEADLINE)).doesNotContain("発送準備を開始する");
    }
}
