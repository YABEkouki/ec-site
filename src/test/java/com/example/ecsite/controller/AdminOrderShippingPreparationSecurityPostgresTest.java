package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URLEncoder;
import java.net.URI;
import java.time.format.DateTimeFormatter;
import org.springframework.mock.web.MockHttpSession;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.OrderShippingPreparationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Full application security, MVC, real templates and service; DB is provided only by the isolated launcher. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminOrderShippingPreparationSecurityPostgresTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired OrderStatusHistoryRepository histories;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean OrderShippingPreparationService preparation;
    @MockitoSpyBean OrderService orderService;
    @MockitoBean PaymentGateway gateway;
    @MockitoSpyBean com.example.ecsite.service.payment.AdminCaptureRequeryService captureRequery;
    @Autowired com.example.ecsite.service.payment.PaymentCaptureStartService captureStart;
    private int expectedGatewayReads;

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private static final String FAILURE = "発送準備を開始できません。変更期限・注文状態・決済状況をご確認ください。";

    private RequestPostProcessor admin(long id, String username) {
        return user(new AdminUserDetails(id, username, "password", true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    private RequestPostProcessor admin() { return admin(7L, "admin-first"); }
    private String postUrl(long id) { return "/admin/orders/" + id + "/start-shipping-preparation"; }
    private String detail(long id, String returnUrl) {
        return "/admin/orders/" + id + "?returnUrl=" + URLEncoder.encode(returnUrl, StandardCharsets.UTF_8);
    }
    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select * from orders where id=?", id);
    }

    @AfterEach
    void cleanup() {
        try {
            if(expectedGatewayReads==0) verifyNoInteractions(gateway);
            else { verify(gateway,times(expectedGatewayReads)).retrievePaymentFlow(anyString());verifyNoMoreInteractions(gateway); }
        }
        finally {
            for (Long id : orderIds) {
                jdbc.update("update orders set shipment_capture_payment_id=null where id=?", id);
                jdbc.update("delete from payment_transactions where payment_id in (select id from payments where order_id=?)", id);
                jdbc.update("delete from payments where order_id=?", id);
                jdbc.update("delete from order_status_histories where order_id=?", id);
                jdbc.update("delete from orders where id=?", id);
            }
            for (Long id : userIds) jdbc.update("delete from users where id=?", id);
        }
    }

    private long fixture(String kind) {
        return new TransactionTemplate(manager).execute(tx -> {
            LocalDateTime now = orders.findCurrentTimeInTokyo();
            User user = new User(); user.setUsername("shipping-ui-" + UUID.randomUUID());
            user.setPassword("password"); user.setEnabled(true); user.setCreatedAt(now); user.setUpdatedAt(now);
            users.saveAndFlush(user); userIds.add(user.getId());
            LocalDateTime orderedAt = now.minusDays(1).toLocalDate().atTime(10, 0);
            Order order = new Order(user.getId(), 100, orderedAt, orderedAt.withHour(14));
            if (!kind.equals("card") && !kind.equals("missing-card")) order.markAsPaid();
            if (kind.equals("before")) ReflectionTestUtils.setField(order, "changeDeadlineAt", now.plusDays(1));
            if (kind.equals("SHIPPED") || kind.equals("CANCELLED"))
                ReflectionTestUtils.setField(order, "status", OrderStatus.valueOf(kind));
            orders.saveAndFlush(order); orderIds.add(order.getId());
            if (kind.equals("card")) {
                Payment payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 100, now);
                payment.setProviderPaymentId("ui_" + UUID.randomUUID(), now); payment.markAuthorized(now);
                payments.saveAndFlush(payment);
                PaymentTransaction authorization = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE,
                        100, 0, UUID.randomUUID().toString(), now);
                authorization.markSucceeded(payment.getProviderPaymentId(), now); transactions.saveAndFlush(authorization);
            }
            histories.saveAndFlush(OrderStatusHistory.create(order, null, order.getStatus(),
                    OrderStatusHistoryActorType.ADMIN, 7L, "history-admin"));
            return order.getId();
        });
    }

    @Test
    void unauthenticatedPostWithCsrfRedirectsToAdminLoginWithoutService() throws Exception {
        mvc.perform(post(postUrl(42)).with(csrf())).andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
        verifyNoInteractions(preparation);
    }

    @Test
    void customerPostWithCsrfIsForbiddenWithoutService() throws Exception {
        mvc.perform(post(postUrl(42)).with(user("customer").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(preparation);
    }

    @Test
    void adminWithoutCsrfIsForbiddenWithoutService() throws Exception {
        mvc.perform(post(postUrl(42)).with(admin())).andExpect(status().isForbidden());
        verifyNoInteractions(preparation);
    }

    @Test
    void getWithAdminAndCsrfCannotStartOperation() throws Exception {
        mvc.perform(get(postUrl(42)).with(admin()).with(csrf())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(preparation);
    }

    @Test
    void paidManualPostPrgAndRetryPersistOriginalSnapshot() throws Exception {
        long id = fixture("manual");
        String returnUrl = "/admin/orders?status=PAID&page=2&size=20&sort=id,desc";
        var before = row(id);
        String username = "<script>first</script>";
        var post = mvc.perform(post(postUrl(id)).with(admin(7L, username)).with(csrf())
                        .param("returnUrl", returnUrl).param("actorId", "999").param("username", "forged"))
                .andExpect(status().isFound()).andExpect(redirectedUrl(detail(id, returnUrl)))
                .andExpect(flash().attribute("successMessage", "発送準備を開始しました。"))
                .andReturn();
        var saved = row(id);
        assertThat(saved).containsEntry("shipping_preparation_started_by_account_id", 7L)
                .containsEntry("shipping_preparation_started_by_username", username)
                .containsEntry("shipment_capture_payment_id", null);
        assertThat(saved.get("shipping_preparation_started_at")).isNotNull();
        var withoutMetadata = new HashMap<>(saved);
        for (String field : List.of("shipping_preparation_started_at", "shipping_preparation_started_by_account_id",
                "shipping_preparation_started_by_username")) withoutMetadata.put(field, null);
        assertThat(withoutMetadata).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_status_histories where order_id=?", Integer.class, id)).isEqualTo(1);
        String html = mvc.perform(get(URI.create(post.getResponse().getRedirectedUrl())).with(admin())
                        .session((MockHttpSession) post.getRequest().getSession(false)))
                .andExpect(status().isOk()).andExpect(model().attribute("returnUrl", returnUrl))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("発送準備を開始しました。", "発送準備開始済み", "&lt;script&gt;first&lt;/script&gt;", "history-admin")
                .doesNotContain("発送準備を開始する", "<script>first</script>");
        String displayedTime = ((java.sql.Timestamp) saved.get("shipping_preparation_started_at")).toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));
        assertThat(html).contains(displayedTime, "開始管理者ID", ">7</dd>");
        mvc.perform(post(postUrl(id)).with(admin(9L, "second")).with(csrf()).param("returnUrl", returnUrl))
                .andExpect(status().isFound()).andExpect(redirectedUrl(detail(id, returnUrl)))
                .andExpect(flash().attribute("successMessage", "この注文は既に発送準備を開始しています。"));
        assertThat(row(id)).isEqualTo(saved);
        verify(preparation, times(2)).start(id);
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success", Integer.class)).isEqualTo(54);
    }

    @ParameterizedTest
    @ValueSource(strings = {"before", "SHIPPED", "CANCELLED", "missing-card"})
    void realBusinessRejectionRedirectsWithSafeFlashWithoutDbChanges(String kind) throws Exception {
        long id = fixture(kind);
        var before = row(id);
        var post = mvc.perform(post(postUrl(id)).with(admin()).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl(detail(id, "/admin/orders")))
                .andExpect(flash().attribute("errorMessage", FAILURE)).andExpect(flash().attributeCount(1)).andReturn();
        String html = mvc.perform(get(URI.create(post.getResponse().getRedirectedUrl())).with(admin())
                        .session((MockHttpSession) post.getRequest().getSession(false)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains(FAILURE).doesNotContain("IllegalStateException", "InvalidOrderStatusException");
        assertThat(row(id)).isEqualTo(before);
        verify(preparation).start(id);
    }

    @Test
    void serviceAccessDeniedIs403WithoutFlash() throws Exception {
        doThrow(new AccessDeniedException("private auth detail")).when(preparation).start(42L);
        mvc.perform(post(postUrl(42)).with(admin()).with(csrf())).andExpect(status().isForbidden())
                .andExpect(flash().attributeCount(0)).andExpect(content().string(""));
        verify(preparation).start(42L);
    }

    @Test
    void unexpectedServiceExceptionUsesSafe500Page() throws Exception {
        doThrow(new UnsupportedOperationException("private payment detail")).when(preparation).start(42L);
        mvc.perform(post(postUrl(42)).with(admin()).with(csrf())).andExpect(status().isInternalServerError())
                .andExpect(view().name("error/internal-server-error"))
                .andExpect(flash().attributeCount(0)).andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private payment detail"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1"})
    void invalidIdsAre400WithoutService(String id) throws Exception {
        mvc.perform(post("/admin/orders/" + id + "/start-shipping-preparation").with(admin()).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(preparation);
    }

    @Test
    void missingOrderUsesExisting404Page() throws Exception {
        mvc.perform(post(postUrl(Long.MAX_VALUE)).with(admin()).with(csrf()))
                .andExpect(status().isNotFound()).andExpect(view().name("error/order_not_found"));
    }

    @Test
    void externalReturnUrlFallsBackToLocalDetail() throws Exception {
        long id = fixture("manual");
        mvc.perform(post(postUrl(id)).with(admin()).with(csrf()).param("returnUrl", "https://evil.example/"))
                .andExpect(status().isFound()).andExpect(redirectedUrl(detail(id, "/admin/orders")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"manual", "card", "missing-card"})
    void realDetailMaintainsExistingFormsHistoryCsrfAndDoesNotMutateOrder(String kind) throws Exception {
        long id = fixture(kind);
        var before = row(id);
        String html = mvc.perform(get("/admin/orders/" + id).with(admin()).param("returnUrl", "/admin/orders?page=2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("history-admin", "発送準備を開始する", "name=\"returnUrl\"", "page=2");
        if (kind.equals("manual")) assertThat(html).doesNotContain("/admin/orders/" + id + "/ship");
        if (kind.equals("card")) assertThat(html).doesNotContain("/admin/orders/" + id + "/capture-and-ship");
        if (kind.equals("missing-card")) assertThat(html).contains("formaction=\"/admin/orders/" + id + "/pay\"");
        assertThat(html).doesNotContain("/admin/orders/" + id + "/cancel");
        var tags = Pattern.compile("</?form\\b[^>]*>").matcher(html);
        int depth = 0;
        while (tags.find()) {
            if (tags.group().startsWith("</")) depth--;
            else depth++;
            assertThat(depth).isBetween(0, 1);
        }
        assertThat(depth).isZero();
        var forms = Pattern.compile("<form\\b[^>]*method=\"post\"[^>]*>(.*?)</form>", Pattern.DOTALL).matcher(html);
        int count = 0;
        while (forms.find()) {
            assertThat(forms.group(1)).contains("name=\"_csrf\"");
            count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(4);
        assertThat(row(id)).isEqualTo(before);
        verifyNoInteractions(preparation);
    }

    @Test
    void salesDashboardExplainsOrderStateBasedTotals() throws Exception {
        String html = mvc.perform(get("/admin/sales").with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("id=\"sales-definition\"", "注文状態が支払済み・発送済み", "確認中・失敗の注文も含まれ得ます", "入金確定額の集計ではありません");
        verifyNoInteractions(gateway, preparation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepared-card", "pending", "captured", "failed", "legacy", "manual"})
    void detailPaymentStateAndShipmentButtonsUseStoredFactsWithoutWrites(String kind) throws Exception {
        long id = fixture(kind.equals("manual") ? "manual" : "card");
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            Order order = orders.findById(id).orElseThrow();
            if (!kind.equals("legacy")) order.beginShippingPreparation(order.getChangeDeadlineAt(), 7L, "<preparer>");
            if (kind.equals("pending") || kind.equals("captured") || kind.equals("failed")) {
                Payment payment = payments.findByOrderIdOrderByCreatedAtAscIdAsc(id).getFirst();
                var now = orders.findCurrentTimeInTokyo();
                order.confirmCardShipment(now, 7L, "admin-first", payment.getId());
                PaymentTransaction capture = new PaymentTransaction(payment, PaymentTransactionType.CAPTURE,
                        order.getTotalAmount(), order.getContentRevision(), UUID.randomUUID().toString(), now,
                        new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN, 7L, "admin-first", "memo"));
                if (kind.equals("captured")) { capture.markSucceeded(payment.getProviderPaymentId(), now); payment.markCaptured(now); order.recordShipmentCaptureSucceeded(payment.getId(), now); }
                if (kind.equals("failed")) capture.markFailed(payment.getProviderPaymentId(), "failed", "PRIVATE_PROVIDER_RESPONSE", now);
                transactions.save(capture);
            }
            if (kind.equals("legacy")) { ReflectionTestUtils.setField(order, "status", OrderStatus.SHIPPED); ReflectionTestUtils.setField(order, "shippedAt", orders.findCurrentTimeInTokyo()); }
        });
        var before = row(id);
        var paymentRows = jdbc.queryForList("select * from payments where order_id=? order by id", id);
        var transactionRows = jdbc.queryForList("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.id", id);
        String html = mvc.perform(get("/admin/orders/" + id).with(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("決済状態", switch(kind) { case "prepared-card" -> "与信済み"; case "pending" -> "売上確定確認中"; case "captured" -> "売上確定済み"; case "failed" -> "決済要確認"; case "manual" -> "手動決済（支払済み）"; default -> "既存の決済情報要確認"; });
        assertThat(html).doesNotContain("PRIVATE_PROVIDER_RESPONSE", "<preparer>");
        if(kind.equals("pending")) {
            String section=html.substring(html.indexOf("<section id=\"capture-requery\""),html.indexOf("</section>",html.indexOf("<section id=\"capture-requery\"")));
            assertThat(section).contains("売上確定結果を再照会", "/admin/orders/"+id+"/capture/requery", "method=\"post\"", "name=\"_csrf\"", "name=\"returnUrl\"")
                .doesNotContain("name=\"paymentId\"", "name=\"transactionId\"", "name=\"actorId\"", "</form><form");
        } else assertThat(html).doesNotContain("capture/requery");
        var formTags=Pattern.compile("</?form\\b[^>]*>").matcher(html);int formDepth=0;
        while(formTags.find()){formDepth+=formTags.group().startsWith("</")?-1:1;assertThat(formDepth).isBetween(0,1);}assertThat(formDepth).isZero();
        if (!kind.equals("legacy")) assertThat(html).contains("&lt;preparer&gt;");
        if (kind.equals("prepared-card")) assertThat(html).contains("発送を確定して売上確定を実行する", "発送済みを先に確定", "/capture-and-ship");
        else assertThat(html).doesNotContain("/capture-and-ship");
        if (kind.equals("manual")) assertThat(html).contains("/admin/orders/" + id + "/ship");
        assertThat(row(id)).isEqualTo(before);
        assertThat(jdbc.queryForList("select * from payments where order_id=? order by id", id)).isEqualTo(paymentRows);
        assertThat(jdbc.queryForList("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.id", id)).isEqualTo(transactionRows);
        verifyNoInteractions(gateway, preparation);
    }

    private long pendingCaptureFixture() {
        long id=fixture("card");
        new TransactionTemplate(manager).executeWithoutResult(tx->{Order order=orders.findById(id).orElseThrow();order.beginShippingPreparation(order.getChangeDeadlineAt(),7L,"preparer");});
        var actor=new AdminUserDetails(7L,"original-shipper","password",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        try {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));
            captureStart.start(id,7L,"original-shipper","original memo");
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        return id;
    }
    @Test void requeryUnauthenticatedRedirectsWithoutService()throws Exception {
        mvc.perform(post("/admin/orders/42/capture/requery").with(csrf())).andExpect(status().isFound()).andExpect(redirectedUrl("/admin/login"));verifyNoInteractions(captureRequery);
    }
    @Test void requeryUserIsForbiddenWithoutService()throws Exception {
        mvc.perform(post("/admin/orders/42/capture/requery").with(user("customer").roles("USER")).with(csrf())).andExpect(status().isForbidden());verifyNoInteractions(captureRequery);
    }
    @Test void requeryAdminWithoutCsrfIsForbiddenWithoutService()throws Exception {
        mvc.perform(post("/admin/orders/42/capture/requery").with(admin())).andExpect(status().isForbidden());verifyNoInteractions(captureRequery);
    }
    @Test void requeryGetCannotRunOperation()throws Exception {
        mvc.perform(get("/admin/orders/42/capture/requery").with(admin()).with(csrf())).andExpect(status().isMethodNotAllowed());verifyNoInteractions(captureRequery);
    }
    @ParameterizedTest @ValueSource(strings={"SUCCEEDED","PROCESSING"})
    void realRequeryPrgKeepsOriginalShipmentAndIgnoresBrowserTargets(String flowStatus)throws Exception {
        long id=pendingCaptureFixture();String provider=jdbc.queryForObject("select provider_payment_id from payments where order_id=?",String.class,id);var before=row(id);
        expectedGatewayReads=1;
        when(gateway.retrievePaymentFlow(provider)).thenReturn(new com.example.ecsite.payment.PaymentFlowState(provider,com.example.ecsite.payment.PaymentFlowStatus.valueOf(flowStatus),null,null));
        String returnUrl="/admin/orders?status=SHIPPED&page=2&size=20&sort=id,desc";
        mvc.perform(post("/admin/orders/"+id+"/capture/requery").with(admin(9L,"new-admin")).with(csrf()).param("returnUrl",returnUrl).param("paymentId","999").param("transactionId","888").param("actorId","777"))
            .andExpect(status().isFound()).andExpect(redirectedUrl(detail(id,returnUrl)))
            .andExpect(flash().attribute(flowStatus.equals("SUCCEEDED")?"successMessage":"errorMessage",flowStatus.equals("SUCCEEDED")?"売上確定済みであることを確認しました。":"売上確定結果を引き続き確認中です。"));
        assertThat(row(id)).containsEntry("status","SHIPPED").containsEntry("shipped_at",before.get("shipped_at")).containsEntry("shipped_by_account_id",7L).containsEntry("shipped_by_username","original-shipper");
        assertThat(row(id).get("paid_at")!=null).isEqualTo(flowStatus.equals("SUCCEEDED"));
        assertThat(jdbc.queryForObject("select count(*) from order_status_histories where order_id=? and to_status='SHIPPED'",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select initiator_id,initiator_username,internal_note from payment_transactions where transaction_type='CAPTURE' and payment_id in(select id from payments where order_id=?)",id)).containsEntry("initiator_id",7L).containsEntry("initiator_username","original-shipper").containsEntry("internal_note","original memo");
        String html=mvc.perform(get("/admin/orders/"+id).with(admin()).param("returnUrl",returnUrl)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("page=2&amp;size=20&amp;sort=id,desc");
        if(flowStatus.equals("SUCCEEDED")){
            assertThat(html).contains("売上確定済み").doesNotContain("capture/requery");var paid=row(id).get("paid_at");
            mvc.perform(post("/admin/orders/"+id+"/capture/requery").with(admin()).with(csrf())).andExpect(status().isFound()).andExpect(flash().attribute("successMessage","売上確定済みであることを確認しました。"));assertThat(row(id).get("paid_at")).isEqualTo(paid);
        } else assertThat(html).contains("売上確定確認中","capture/requery");
        verify(captureRequery,flowStatus.equals("SUCCEEDED")?times(2):times(1)).requery(id);
    }
    @Test void requeryServiceDenialIs403NotBusinessFlash()throws Exception {
        doThrow(new AccessDeniedException("private")).when(captureRequery).requery(42L);
        mvc.perform(post("/admin/orders/42/capture/requery").with(admin()).with(csrf())).andExpect(status().isForbidden()).andExpect(flash().attributeCount(0));
    }
    @Test void requeryUnexpectedFailureUsesSafe500()throws Exception {
        doThrow(new NullPointerException("PRIVATE_REQUERY_ERROR")).when(captureRequery).requery(42L);
        String html=mvc.perform(post("/admin/orders/42/capture/requery").with(admin()).with(csrf())).andExpect(status().isInternalServerError()).andExpect(flash().attributeCount(0)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("PRIVATE_REQUERY_ERROR");
    }
    @Test
    void partialMetadataWarningUsesMockedGetBecauseV53ForbidsPersistingPartialRow() throws Exception {
        long id = fixture("manual");
        Order partial = new Order(1L, 100, LocalDateTime.of(2026, 10, 15, 10, 0), LocalDateTime.of(2026, 10, 15, 14, 0));
        ReflectionTestUtils.setField(partial, "id", id);
        ReflectionTestUtils.setField(partial, "shippingPreparationStartedByAccountId", 7L);
        doReturn(partial).when(orderService).findOrderWithItems(id);
        String html = mvc.perform(get("/admin/orders/" + id).with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("発送準備情報に不整合").doesNotContain("発送準備を開始する");
        doThrow(new IllegalStateException("private partial metadata detail")).when(preparation).start(id);
        mvc.perform(post(postUrl(id)).with(admin()).with(csrf())).andExpect(status().isFound())
                .andExpect(flash().attribute("errorMessage", FAILURE));
    }
}
