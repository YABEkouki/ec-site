package com.example.ecsite.payment.safety;

import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.order.evidence.OrderChangeRoutePolicy.Route;
import com.example.ecsite.support.CaptureConnectionTest;
import java.util.*;
import org.junit.jupiter.api.*;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Feature141-A: V109 on a database that holds decreases made before the route was closed. */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@CaptureConnectionTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyItemsClosureUpgradePostgresTest {
    private static PostgreSQLContainer database;
    @DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry properties) {
        PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();
        database = OrderEditAdmissionFoundationPostgresTest.commitTrackingContainer();
        database.start();
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
        properties.add("spring.flyway.target", () -> "108");
        properties.add("spring.flyway.url", database::getJdbcUrl);
        properties.add("spring.flyway.user", database::getUsername);
        properties.add("spring.flyway.password", database::getPassword);
    }
    @AfterAll static void stopDatabase() { if (database != null) database.stop(); }
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.flywaydb.core.Flyway flyway;
    @MockitoBean PaymentGateway gateway;
    private CustomerOrderChangeSavedHistoryFixture fixture;
    @BeforeEach void prepare(TestInfo info) { fixture = new CustomerOrderChangeSavedHistoryFixture(context, gateway, info); }
    @AfterEach void cleanup() { if (fixture != null) fixture.close(); }

    @Test void savedDecreasesSurviveAndAreStillShown() throws Exception {
        var order = fixture.legacyWithTwoItems("閉じる前の商品");
        fixture.decrease(order, 0, 2, true);
        var before = saved();
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where movement_type='ORDER_ITEM_CHANGE'", Integer.class)).isEqualTo(2);

        org.flywaydb.core.Flyway.configure().configuration(flyway.getConfiguration()).target("latest").load().migrate();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='109' and success", Integer.class)).isOne();
        assertThat(saved()).isEqualTo(before);

        String html = mvc.perform(get("/orders/{id}/content-changes", order.order()).with(user(new CustomUserDetails(order.owner(),
                "items-buyer", "unused", true, List.of(new SimpleGrantedAuthority("ROLE_USER"))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("閉じる前の商品", "削除商品", "12,000円", "6,000円").doesNotContain("履歴を表示できません");

        // Later unrelated updates of a saved movement are not blocked; a further decrease is.
        jdbc.update("update stock_movements set movement_type='ORDER_ITEM_CHANGE' where order_id=?", order.order());
        assertThatThrownBy(() -> fixture.decrease(order, 1, 1, false)).hasStackTraceContaining("The legacy order item change is closed");
        assertThat(saved()).isEqualTo(before);

        // A changed order can still start the order edit from its current content, and the edit completes.
        when(gateway.observeOrderAuthorization(any())).thenAnswer(call ->
                com.example.ecsite.payment.payjp.MockOrderAuthorizationTransport.observe(call.getArgument(0)));
        var enrolled = fixture.enroll(order);
        reset(gateway);
        long added = fixture.writer.helper().product(enrolled, 6000);
        fixture.apply(enrolled, List.of(new Line(null, added, 1)), Route.RETAIN_AUTH);
        assertThat(jdbc.queryForObject("select count(*) from order_content_change_histories where order_id=?", Integer.class, order.order())).isEqualTo(2);
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
