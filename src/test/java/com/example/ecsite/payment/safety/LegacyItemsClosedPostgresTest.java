package com.example.ecsite.payment.safety;

import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.order.evidence.OrderChangeRoutePolicy.Route;
import com.example.ecsite.support.CaptureConnectionTest;
import com.example.ecsite.support.LegacyItemDecreaseFixture;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Feature141-A on the latest schema: /orders/{id}/items only guides, and V109 refuses its rows. */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@CaptureConnectionTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyItemsClosedPostgresTest {
    private static final String MESSAGE = "注文内容の変更は、注文詳細の「注文内容を変更する」から行ってください。";
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
    @Autowired PlatformTransactionManager manager;
    @MockitoBean PaymentGateway gateway;
    private CustomerOrderChangeSavedHistoryFixture fixture;
    @BeforeEach void prepare(TestInfo info) { fixture = new CustomerOrderChangeSavedHistoryFixture(context, gateway, info); }
    @AfterEach void cleanup() { if (fixture != null) fixture.close(); }

    enum Content { UNENROLLED, ENROLLED, CHANGE_PENDING }

    @ParameterizedTest @EnumSource(Content.class)
    void everyRouteOnlyGuidesToTheOrderDetail(Content content) throws Exception {
        var order = order(content);
        var before = saved();
        for (var request : requests(order))
            mvc.perform(request.with(customer(order.owner())).with(csrf()))
                    .andExpect(status().isFound()).andExpect(redirectedUrl("/orders/" + order.order()))
                    .andExpect(flash().attribute("errorMessage", MESSAGE));
        assertThat(saved()).isEqualTo(before);
        verifyNoInteractions(gateway);
        mvc.perform(get("/orders/" + order.order()).flashAttr("errorMessage", MESSAGE).with(customer(order.owner())))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(MESSAGE)));
    }

    @Test void loginCsrfAndOwnershipAreStillRequired() throws Exception {
        var order = order(Content.UNENROLLED);
        var before = saved();
        mvc.perform(get("/orders/{id}/items", order.order())).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        for (String path : List.of("/items", "/items/confirm", "/items/back"))
            mvc.perform(post("/orders/" + order.order() + path).with(customer(order.owner()))).andExpect(status().isForbidden());
        // The guide reads nothing, so ownership is checked by the page it leads to.
        mvc.perform(get("/orders/{id}/items", order.order()).with(customer(order.owner() + 1000000)))
                .andExpect(redirectedUrl("/orders/" + order.order()));
        mvc.perform(get("/orders/{id}", order.order()).with(customer(order.owner() + 1000000))).andExpect(status().isNotFound());
        assertThat(saved()).isEqualTo(before);
        verifyNoInteractions(gateway);
    }

    @Test void cancellationPageGuidesInsteadOfLinkingToTheClosedRoute() throws Exception {
        var order = order(Content.UNENROLLED);
        mvc.perform(get("/orders/{id}/cancel", order.order()).with(customer(order.owner()))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"cancel-change-guidance\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("商品や数量を変更する場合は、注文詳細の「注文内容を変更する」から行ってください。")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/items"))));
    }

    @Test void databaseRefusesTheRowsOfTheClosedRoute() {
        var order = order(Content.UNENROLLED);
        var before = saved();
        refused(() -> LegacyItemDecreaseFixture.decrease(context, order.order(), order.owner(), "customer", Map.of(order.item(), 2)));
        refused(() -> movement(order, "ORDER_ITEM_CHANGE"));
        assertThat(saved()).isEqualTo(before);

        movement(order, "ORDER_CANCELLATION");
        var recorded = saved();
        refused(() -> jdbc.update("update stock_movements set movement_type='ORDER_ITEM_CHANGE' where order_id=?", order.order()));
        // The history is judged at commit, not when it is inserted.
        refusedHistory(() -> new TransactionTemplate(manager).executeWithoutResult(s -> {
            history(order.order());
            assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?", Integer.class, order.order())).isOne();
        }));
        assertThat(saved()).isEqualTo(recorded);
    }

    @ParameterizedTest @ValueSource(strings = {"RETAIN_AUTH", "REAUTHORIZE"})
    void orderEditsStillComplete(String route) {
        boolean retained = route.equals("RETAIN_AUTH");
        var f = OrderEditAdmissionServicePostgresTest.fixture(false);
        long added = fixture.writer.helper().product(f, retained ? 6000 : 7000);
        fixture.apply(f, retained ? List.of(new Line(null, added, 1))
                : List.of(new Line(f.item(), f.product(), 1), new Line(null, added, 1)), Route.valueOf(route));
        assertThat(jdbc.queryForObject("""
                select count(*) from order_content_change_histories h join order_change_operations o
                    on o.content_change_history_id=h.id and o.order_id=h.order_id and o.execution_version=1 where h.order_id=?
                """, Integer.class, f.order())).isOne();
    }

    private CustomerOrderChangeSavedHistoryFixture.Legacy order(Content content) {
        var order = fixture.legacyWithTwoItems("閉じた経路の商品");
        if (content != Content.UNENROLLED) {
            when(gateway.observeOrderAuthorization(any())).thenAnswer(call ->
                    com.example.ecsite.payment.payjp.MockOrderAuthorizationTransport.observe(call.getArgument(0)));
            var enrolled = fixture.enroll(order);
            reset(gateway);
            if (content == Content.CHANGE_PENDING) {
                long added = fixture.writer.helper().product(enrolled, 6000);
                fixture.writer.accepted(enrolled, List.of(new Line(null, added, 1)), Route.RETAIN_AUTH);
            }
        }
        clearInvocations(gateway);
        return order;
    }

    private List<MockHttpServletRequestBuilder> requests(CustomerOrderChangeSavedHistoryFixture.Legacy order) {
        var requests = new ArrayList<MockHttpServletRequestBuilder>();
        requests.add(get("/orders/{id}/items", order.order()));
        for (String path : List.of("/items/confirm", "/items/back", "/items"))
            requests.add(post("/orders/" + order.order() + path).param("contentRevision", "0")
                    .param("items[0].orderItemId", Long.toString(order.item())).param("items[0].quantity", "1")
                    .param("items[1].orderItemId", Long.toString(order.removed())).param("items[1].quantity", "0"));
        return requests;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer(long id) {
        return user(new CustomUserDetails(id, "items-buyer", "unused", true, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private void movement(CustomerOrderChangeSavedHistoryFixture.Legacy order, String type) {
        jdbc.update("""
                insert into stock_movements(product_id,product_name,movement_type,quantity,stock_before,stock_after,
                    changed_by_type,changed_by_username,order_id,changed_at)
                values(?,'item',?,1,1,2,'SYSTEM','SYSTEM',?,timezone('Asia/Tokyo',clock_timestamp()))
                """, order.product(), type, order.order());
    }

    private void history(long order) {
        jdbc.update("""
                insert into order_content_change_histories(order_id,change_source,changed_by_type,changed_by_username,
                    old_item_subtotal,new_item_subtotal,old_charge_total,new_charge_total,old_tax_amount,new_tax_amount,
                    old_total_amount,new_total_amount) values (?,'CUSTOMER','USER','fixture',12000,9000,0,0,0,0,12000,9000)
                """, order);
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        refused(call, "The legacy order item change is closed");
    }
    private static void refusedHistory(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        refused(call, "An order content change requires its order change operation");
    }
    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        assertThatThrownBy(call).satisfies(e -> {
            Throwable t = e;
            while (t != null && !(t instanceof java.sql.SQLException)) t = t.getCause();
            assertThat(t).as("%s", e).isNotNull();
            assertThat(((java.sql.SQLException) t).getSQLState()).isEqualTo("23514");
            assertThat(t).hasMessageContaining(message);
        });
    }

    private Map<String, String> saved() {
        var rows = new TreeMap<String, String>();
        for (String table : List.of("orders", "order_items", "order_charges", "products", "payments", "payment_transactions",
                "stock_movements", "order_content_change_histories", "order_content_change_items", "order_change_operations",
                "order_edit_admissions", "order_payment_evidence_epochs", "customer_order_notifications"))
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(r) order by to_jsonb(r)::text),'[]'::jsonb)::text from " + table + " r", String.class));
        return rows;
    }
}
