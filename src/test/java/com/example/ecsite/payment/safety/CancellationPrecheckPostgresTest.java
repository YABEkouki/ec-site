package com.example.ecsite.payment.safety;

import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.payment.*;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.support.CaptureConnectionTest;
import com.example.ecsite.support.LegacyItemDecreaseFixture;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Feature141-B on the latest schema: a new cancellation that could not be applied is refused before it is sent. */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@CaptureConnectionTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CancellationPrecheckPostgresTest {
    private static PostgreSQLContainer database;
    @DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry properties) {
        PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();
        database = OrderEditAdmissionFoundationPostgresTest.commitTrackingContainer();
        database.start();
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
        properties.add("spring.flyway.url", database::getJdbcUrl);
        properties.add("spring.flyway.user", database::getUsername);
        properties.add("spring.flyway.password", database::getPassword);
    }
    @AfterAll static void stopDatabase() { if (database != null) database.stop(); }
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentCancellationService cancellation;
    @Autowired AdminPaymentCancellationService adminCancellation;
    @MockitoSpyBean OrderPaymentEvidenceGuard evidenceGuard;
    @MockitoBean PaymentGateway gateway;
    private CustomerOrderChangeSavedHistoryFixture fixture;
    @BeforeEach void prepare(TestInfo info) { fixture = new CustomerOrderChangeSavedHistoryFixture(context, gateway, info); }
    @AfterEach void cleanup() { reset(evidenceGuard); if (fixture != null) fixture.close(); }

    enum Mismatch { AUTHORIZATION_EXCEEDED, REVISION_ONLY, BROKEN_EVIDENCE }

    @ParameterizedTest @EnumSource(Mismatch.class)
    void newCancellationIsRefusedBeforeItIsSent(Mismatch mismatch) throws Exception {
        var order = mismatched(mismatch);
        var before = saved(order.order());
        String message = "注文内容とカード決済の内容を確認できないため、キャンセルを受け付けられません。注文番号「"
                + order.order() + "」を添えてお問い合わせください。";

        assertThatThrownBy(() -> cancellation.cancelForUser(order.order(), order.owner(), "customer"))
                .isInstanceOf(InvalidOrderStatusException.class).hasMessage(message);
        assertThatThrownBy(() -> adminCancellation.cancel(order.order(), 1L, "admin", null))
                .isInstanceOf(InvalidOrderStatusException.class).hasMessage(message);
        mvc.perform(post("/orders/{id}/cancel", order.order()).with(customer(order.owner())).with(csrf()))
                .andExpect(redirectedUrl("/orders/" + order.order())).andExpect(flash().attribute("errorMessage", message));

        assertThat(saved(order.order())).isEqualTo(before);
        verifyNoInteractions(gateway);
    }

    @Test void theClosedWindowIsStillReportedFirst() {
        var order = mismatched(Mismatch.REVISION_ONLY);
        jdbc.update("update orders set ordered_at=timezone('Asia/Tokyo',clock_timestamp())-interval '2 days',"
                + "change_deadline_at=timezone('Asia/Tokyo',clock_timestamp())-interval '1 hour' where id=?", order.order());
        assertThatThrownBy(() -> cancellation.cancelForUser(order.order(), order.owner(), "customer"))
                .isInstanceOf(InvalidOrderStatusException.class).hasMessageNotContaining("お問い合わせください");
        verifyNoInteractions(gateway);
    }

    enum Consistent { UNCHANGED, DECREASED, ENROLLED }

    @ParameterizedTest @EnumSource(Consistent.class)
    void consistentOrdersAreStillCancelled(Consistent content) {
        var order = fixture.legacyWithTwoItems("取消できる商品");
        if (content == Consistent.DECREASED)
            LegacyItemDecreaseFixture.decreaseContentOnly(context, order.order(), order.owner(), Map.of(order.item(), 2));
        if (content == Consistent.ENROLLED) enroll(order);
        String flow = flow(order.order());
        when(gateway.cancelAuthorization(any())).thenReturn(new CancellationResult(CancellationResultStatus.CANCELLED, flow));

        assertThat(cancellation.cancelForUser(order.order(), order.owner(), "customer").status())
                .isEqualTo(CancellationResultStatus.CANCELLED);

        verify(gateway).cancelAuthorization(any());
        assertThat(jdbc.queryForObject("select status from orders where id=?", String.class, order.order())).isEqualTo("CANCELLED");
        assertThat(cancels(order.order())).containsExactly("SUCCEEDED");
    }

    /** Accepted before the check existed: only looked up, as before, even when its result cannot be applied. */
    @Test void anExistingPendingCancellationIsOnlyLookedUp() {
        var order = fixture.legacyWithTwoItems("取消中の商品");
        String flow = flow(order.order());
        when(gateway.cancelAuthorization(any())).thenReturn(new CancellationResult(CancellationResultStatus.PENDING, flow));
        when(gateway.retrievePaymentFlow(flow)).thenReturn(new PaymentFlowState(flow, PaymentFlowStatus.REQUIRES_CAPTURE, null, null));
        assertThat(cancellation.cancelForUser(order.order(), order.owner(), "customer").status()).isEqualTo(CancellationResultStatus.PENDING);

        var blocked = mock(PaymentContentEvidence.class);
        when(blocked.state()).thenReturn(PaymentContentEvidence.State.BLOCKED);
        doReturn(blocked).when(evidenceGuard).inspect(any());
        assertThat(cancellation.cancelForUser(order.order(), order.owner(), "customer").status()).isEqualTo(CancellationResultStatus.PENDING);

        verify(gateway, times(1)).cancelAuthorization(any());
        verify(gateway).retrievePaymentFlow(flow);
        assertThat(cancels(order.order())).containsExactly("PENDING");
    }

    private CustomerOrderChangeSavedHistoryFixture.Legacy mismatched(Mismatch mismatch) {
        var order = fixture.legacyWithTwoItems("取消できない商品");
        long id = order.order();
        switch (mismatch) {
            case AUTHORIZATION_EXCEEDED -> {
                jdbc.update("update order_items set price=1500,subtotal=4500 where id=?", order.item());
                jdbc.update("update order_items set price=500,subtotal=500 where id=?", order.removed());
                jdbc.update("update orders set item_subtotal=5000,total_amount=5000,tax_amount=454 where id=?", id);
                jdbc.update("update payments set amount=5000 where order_id=?", id);
                jdbc.update("update payment_transactions set amount=5000 where payment_id in (select id from payments where order_id=?)", id);
                // Below the free shipping line the fee makes the total exceed the authorized 5,000 yen.
                LegacyItemDecreaseFixture.decreaseContentOnly(context, id, order.owner(), Map.of(order.removed(), 0));
                assertThat(jdbc.queryForObject("select total_amount from orders where id=?", Integer.class, id)).isGreaterThan(5000);
            }
            case REVISION_ONLY -> jdbc.update("update orders set content_revision=content_revision+1 where id=?", id);
            case BROKEN_EVIDENCE -> {
                enroll(order);
                var blocked = mock(PaymentContentEvidence.class);
                when(blocked.state()).thenReturn(PaymentContentEvidence.State.BLOCKED);
                doReturn(blocked).when(evidenceGuard).inspect(any());
            }
        }
        clearInvocations(gateway);
        return order;
    }

    private void enroll(CustomerOrderChangeSavedHistoryFixture.Legacy order) {
        when(gateway.observeOrderAuthorization(any())).thenAnswer(call ->
                com.example.ecsite.payment.payjp.MockOrderAuthorizationTransport.observe(call.getArgument(0)));
        fixture.enroll(order);
        reset(gateway);
    }

    private String flow(long order) {
        return jdbc.queryForObject("select provider_payment_id from payments where order_id=?", String.class, order);
    }

    private List<String> cancels(long order) {
        return jdbc.queryForList("""
                select t.status from payment_transactions t join payments p on p.id=t.payment_id
                where p.order_id=? and t.transaction_type='CANCEL'
                """, String.class, order);
    }

    private Map<String, List<String>> saved(long order) {
        var rows = new LinkedHashMap<String, List<String>>();
        rows.put("orders", rows("select to_jsonb(o)::text from orders o where id=?", order));
        rows.put("payments", rows("select to_jsonb(p)::text from payments p where order_id=? order by id", order));
        rows.put("transactions", rows("select to_jsonb(t)::text from payment_transactions t where payment_id in (select id from payments where order_id=?) order by id", order));
        rows.put("movements", rows("select to_jsonb(m)::text from stock_movements m where order_id=? order by id", order));
        rows.put("histories", rows("select to_jsonb(h)::text from order_status_histories h where order_id=? order by id", order));
        rows.put("notifications", rows("select to_jsonb(n)::text from customer_order_notifications n where order_id=? order by id", order));
        return rows;
    }
    private List<String> rows(String sql, long order) { return jdbc.queryForList(sql, String.class, order); }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer(long id) {
        return user(new CustomUserDetails(id, "cancel-buyer", "unused", true, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
