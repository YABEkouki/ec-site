package com.example.ecsite.payment.safety;

import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.order.evidence.OrderChangeRoutePolicy.Route;
import com.example.ecsite.service.orderchange.LegacyRecoveredOrderChangeHistoryFixture;
import com.example.ecsite.support.CaptureConnectionTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real saves, owned JDBC Reader, pure Verifier, ViewService and actual secured Thymeleaf rendering. */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@CaptureConnectionTest
@Import(CustomerOrderChangeSavedHistoryWebPostgresTest.SqlObservation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerOrderChangeSavedHistoryWebPostgresTest {
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
    @Autowired DataSource source;
    @MockitoBean PaymentGateway gateway;
    private CustomerOrderChangeSavedHistoryFixture fixture;

    @BeforeEach void prepare(TestInfo info) {
        fixture = new CustomerOrderChangeSavedHistoryFixture(context, gateway, info);
    }
    @AfterEach void cleanup() { if (fixture != null) fixture.close(); }

    @Test void retainedDecreaseRendersSavedAmountsAndTokyoCompletionTime() throws Exception {
        var f = OrderEditAdmissionServicePostgresTest.fixture(false);
        fixture.apply(f, List.of(new Line(f.item(), f.product(), 1)), Route.RETAIN_AUTH);
        var sections = sections(owned(f.order(), f.customer()));
        assertThat(sections).hasSize(1);
        assertItems(sections.getFirst(), row("変更", "保存商品名", "保存商品名", "2", "1", "3,000円", "3,000円", "6,000円", "3,000円"));
        assertAmounts(sections.getFirst(), "6,000円", "3,000円", "0円", "550円", "545円", "322円", "6,000円", "3,550円");
        assertCompletionTime(sections.getFirst(), f.order());
    }

    @Test void reauthorizationRendersActualRemovalAndAdditionWithoutFinancialEvidence() throws Exception {
        var f = OrderEditAdmissionServicePostgresTest.fixture(false);
        long added = fixture.writer.helper().product(f, 7000);
        fixture.apply(f, List.of(new Line(null, added, 1)), Route.REAUTHORIZE);
        String html = owned(f.order(), f.customer());
        var section = sections(html).getFirst();
        assertItems(section,
                row("削除", "保存商品名", "—", "2", "—", "3,000円", "—", "6,000円", "—"),
                row("追加", "—", "added product", "—", "1", "—", "7,000円", "—", "7,000円"));
        assertAmounts(section, "6,000円", "7,000円", "0円", "0円", "545円", "636円", "6,000円", "7,000円");
        assertCompletionTime(section, f.order());
        var states = fixture.writer.db.jdbc.queryForList("select status from payment_transactions where order_change_order_id=?", String.class, f.order());
        assertThat(states).hasSize(2).containsOnly("SUCCEEDED");
    }

    @Test void legacyDecreaseRemovalAndShippingChangeRenderBothSavedHistories() throws Exception {
        var f = fixture.legacyWithTwoItems("旧保存商品");
        fixture.decrease(f, 0, 2, true);
        fixture.decrease(f, 1, 1, false);
        var sections = sections(owned(f.order(), f.owner()));
        assertThat(sections).hasSize(2);
        assertItems(sections.get(0), row("変更", "旧保存商品", "旧保存商品", "2", "1", "3,000円", "3,000円", "6,000円", "3,000円"));
        assertAmounts(sections.get(0), "6,000円", "3,000円", "0円", "550円", "545円", "322円", "6,000円", "3,550円");
        assertItems(sections.get(1),
                row("変更", "旧保存商品", "旧保存商品", "3", "2", "3,000円", "3,000円", "9,000円", "6,000円"),
                row("削除", "削除商品", "—", "1", "—", "3,000円", "—", "3,000円", "—"));
        assertAmounts(sections.get(1), "12,000円", "6,000円", "0円", "0円", "1,090円", "545円", "12,000円", "6,000円");
        assertLegacyTimes(sections, f.order());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void legacyReauthorizationAndRecoveryRenderSavedIncrease(boolean recovered) throws Exception {
        var f = recovered ? LegacyRecoveredOrderChangeHistoryFixture.complete(context, gateway)
                : LegacyRecoveredOrderChangeHistoryFixture.completeNormally(context, gateway);
        var sections = sections(owned(f.orderId(), f.customerId()));
        assertThat(sections).hasSize(1);
        assertItems(sections.getFirst(), row("変更", "ordered", "ordered", "2", "3", "100円", "100円", "200円", "300円"));
        // All amounts are hand calculated from the fixture's 100-yen item and 550-yen shipping, at 10% inclusive tax.
        assertAmounts(sections.getFirst(), "200円", "300円", "550円", "550円", "68円", "77円", "750円", "850円");
        assertLegacyTimes(sections, f.orderId());
    }

    @Test void mixedLegacyAndEqualTotalReplacementExcludePendingAdmissionAndHaveStableOrder() throws Exception {
        var old = fixture.legacyWithTwoItems("混在旧商品");
        fixture.decrease(old, 0, 2, true);
        when(gateway.observeOrderAuthorization(any())).thenAnswer(call -> com.example.ecsite.payment.payjp.MockOrderAuthorizationTransport.observe(call.getArgument(0)));
        var f = fixture.enroll(old);
        // Enrollment and execution use separate transport stubs; do not invoke the enrollment answer while restubbing.
        reset(gateway);
        long added = fixture.writer.helper().product(f, 6000);
        fixture.apply(f, List.of(new Line(null, added, 1)), Route.RETAIN_AUTH);
        long pendingProduct = fixture.writer.helper().product(f, 7000);
        fixture.writer.accepted(f, List.of(new Line(null, pendingProduct, 1)), Route.RETAIN_AUTH);
        var sections = sections(owned(f.order(), f.customer()));
        assertThat(sections).hasSize(2);
        assertItems(sections.get(0),
                row("削除", "混在旧商品", "—", "2", "—", "3,000円", "—", "6,000円", "—"),
                row("追加", "—", "added product", "—", "1", "—", "6,000円", "—", "6,000円"));
        assertAmounts(sections.get(0), "6,000円", "6,000円", "0円", "0円", "545円", "545円", "6,000円", "6,000円");
        assertAmounts(sections.get(1), "12,000円", "6,000円", "0円", "0円", "1,090円", "545円", "12,000円", "6,000円");
        assertThat(sections(owned(f.order(), f.customer()))).isEqualTo(sections);
    }

    @Test void savedHtmlCharactersAreEscaped() throws Exception {
        var f = fixture.legacyWithTwoItems("<script>alert('saved')</script>");
        fixture.decrease(f, 0, 2, true);
        String html = owned(f.order(), f.owner());
        assertThat(html).contains("&lt;script&gt;alert(&#39;saved&#39;)&lt;/script&gt;").doesNotContain("<script>");
        assertItems(sections(html).getFirst(),
                row("変更", "<script>alert('saved')</script>", "<script>alert('saved')</script>", "3", "2", "3,000円", "3,000円", "9,000円", "6,000円"),
                row("削除", "削除商品", "—", "1", "—", "3,000円", "—", "3,000円", "—"));
    }

    @Test void foreignAndMissingOrdersHaveIdentical404WithoutEvidenceSql() throws Exception {
        var f = fixture.legacyWithTwoItems("他人の商品"); fixture.decrease(f, 0, 2, true);
        var foreign = request(f.order(), f.owner() + 1000000, 404);
        assertOwnershipOnly();
        var missing = request(Long.MAX_VALUE, f.owner(), 404);
        assertOwnershipOnly();
        assertThat(foreign).isEqualTo(missing).doesNotContain("他人の商品");
    }

    private String owned(long order, long owner) throws Exception {
        String html = request(order, owner, 200);
        assertThat(html).contains("日本時間", "class=\"change-entry\"").doesNotContain("履歴を表示できません", "一部の注文変更履歴");
        for (String flow : fixture.writer.db.jdbc.queryForList("select provider_payment_id from payments where order_id=?", String.class, order))
            if (flow != null) assertThat(html).doesNotContain(flow);
        for (String operation : fixture.writer.db.jdbc.queryForList("select id::text from order_change_operations where order_id=?", String.class, order))
            assertThat(html).doesNotContain(operation);
        return html;
    }

    private String request(long order, long owner, int expectedStatus) throws Exception {
        var observer = (ObservedSource) source;
        observer.recorder.sql.clear(); clearInvocations(gateway);
        var principal = new CustomUserDetails(owner, "history-buyer", "unused", true, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        String html = mvc.perform(get("/orders/{id}/content-changes", order).with(user(principal)))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        verifyNoInteractions(gateway);
        assertThat(observer.recorder.sql).isNotEmpty().allSatisfy(sql -> assertThat(sql.stripLeading().toLowerCase(Locale.ROOT)).startsWith("select"));
        assertThat(html).doesNotContain("provider_payment_id", "application_history_id", "admission_id", "operation_id",
                "evidence_digest", "request_payload", "financial_payload", "SNAPSHOT_CHAIN", "HISTORY_DIFFERENCE");
        return html;
    }

    private void assertOwnershipOnly() {
        assertThat(((ObservedSource) source).recorder.sql).singleElement().asString().contains("from orders where id=");
    }

    private void assertCompletionTime(String section, long order) {
        var committed = fixture.writer.db.jdbc.queryForObject("""
                select recorded_at from order_edit_admission_events where admission_id in
                    (select id from order_edit_admissions where order_id=? and execution_state='COMPLETED')
                    and after_execution='COMPLETED' order by recorded_at desc limit 1
                """, OffsetDateTime.class, order);
        assertThat(section).contains(committed.atZoneSameInstant(ZoneId.of("Asia/Tokyo")).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")));
    }

    private void assertLegacyTimes(List<String> sections, long order) {
        var times = fixture.writer.db.jdbc.queryForList("select changed_at from order_content_change_histories where order_id=? order by changed_at desc,id desc", LocalDateTime.class, order);
        assertThat(times).hasSize(sections.size());
        for (int i = 0; i < times.size(); i++) assertThat(sections.get(i)).contains(times.get(i).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")));
    }

    private static List<String> row(String... cells) { return List.of(cells); }
    @SafeVarargs private static void assertItems(String section, List<String>... rows) {
        assertThat(tableRows(section, 0).subList(1, tableRows(section, 0).size())).containsExactly(rows);
    }
    private static void assertAmounts(String section, String oldSubtotal, String newSubtotal, String oldShipping, String newShipping,
            String oldTax, String newTax, String oldTotal, String newTotal) {
        assertThat(tableRows(section, 1)).containsExactly(row("項目", "変更前", "変更後"),
                row("商品小計（税込）", oldSubtotal, newSubtotal), row("送料（税込）", oldShipping, newShipping),
                row("うち消費税", oldTax, newTax), row("注文合計（税込）", oldTotal, newTotal));
    }
    private static List<String> sections(String html) { return fragments(html, "<section\\b[^>]*class=\"change-entry\"[^>]*>(.*?)</section>"); }
    private static List<List<String>> tableRows(String section, int index) {
        String table = fragments(section, "<table\\b[^>]*>(.*?)</table>").get(index);
        return fragments(table, "<tr\\b[^>]*>(.*?)</tr>").stream().map(r -> fragments(r, "<t[dh]\\b[^>]*>(.*?)</t[dh]>")
                .stream().map(cell -> cell.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replaceAll("\\s+", " ").strip()).toList()).toList();
    }
    private static List<String> fragments(String html, String regex) {
        var matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(html); var result = new ArrayList<String>();
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlObservation {
        @Bean static BeanPostProcessor observeDataSource() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return name.equals("dataSource") && bean instanceof DataSource ds ? new ObservedSource(ds) : bean;
                }
            };
        }
    }
    static final class ObservedSource extends DelegatingDataSource implements AutoCloseable {
        final CustomerOrderChangeEvidencePostgresTest.RecordingSource recorder;
        ObservedSource(DataSource target) { super(target); recorder = new CustomerOrderChangeEvidencePostgresTest.RecordingSource(target); }
        @Override public Connection getConnection() throws SQLException { return observeStatements(recorder.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return observeStatements(recorder.getConnection(user, password)); }
        // The existing recorder covers prepared statements. Also observe plain/batched statements and callable SQL.
        private Connection observeStatements(Connection connection) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(connection, args);
                    if (method.getName().equals("prepareCall")) recorder.sql.add((String) args[0]);
                    if (method.getName().equals("createStatement")) {
                        Statement statement = (Statement) result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class}, (p, m, a) -> {
                            if ((m.getName().startsWith("execute") || m.getName().equals("addBatch"))
                                    && a != null && a.length > 0 && a[0] instanceof String sql) recorder.sql.add(sql);
                            try { return m.invoke(statement, a); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
                    }
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        @Override public void close() throws Exception { if (getTargetDataSource() instanceof AutoCloseable resource) resource.close(); }
    }
}
