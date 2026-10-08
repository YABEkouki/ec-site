package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.ecsite.entity.*;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.AdminPaymentDiscrepancyService;
import com.example.ecsite.service.PaymentDiscrepancyConflictException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.example.ecsite.service.payment.PaymentDiscrepancyConcurrencyPostgresTest.ReadSlot;
import com.example.ecsite.service.payment.PaymentDiscrepancyConcurrencyPostgresTest.SelectGate;

/** Step 5-2 expectations: reject stale updates, without implementing recovery. */
@DataJpaTest(properties = {
        "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false",
        "spring.flyway.enabled=true", "spring.flyway.clean-disabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never",
        "spring.datasource.hikari.maximum-pool-size=5", "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentDiscrepancyAuditItemService.class, PaymentDiscrepancyEvaluator.class,
        AdminPaymentDiscrepancyService.class, PaymentDiscrepancyConcurrencyPostgresTest.SynchronizationConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class PaymentDiscrepancyOptimisticLockPostgresTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 5, 10, 0);
    private static final LocalDateTime AUDITED = LocalDateTime.of(2026, 10, 6, 12, 0);
    private static final String AUDIT_SELECT = "findByPaymentIdAndLocalStatusAndProviderStatusAndStatus";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature113_version").withUsername("feature113").withPassword("feature113-test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired private PaymentDiscrepancyRepository discrepancies;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderRepository orders;
    @Autowired private UserRepository users;
    @Autowired private PaymentDiscrepancyAuditItemService audit;
    @Autowired private AdminPaymentDiscrepancyService admin;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;
    @Autowired private SelectGate gate;
    @MockitoBean private PaymentGateway gateway;
    private ExecutorService workers;

    @BeforeEach
    void setUp() throws Exception {
        workers = Executors.newFixedThreadPool(2);
        gate.reset();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
        }
        try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
        }
        flyway.validate();
        assertThat(flyway.info().all()).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        gate.releaseAll();
        workers.shutdownNow();
        assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void migrationInitializesExistingOpenAndResolvedRowsToZero() {
        Fixture f = fixture(1);
        // A separate schema exercises an actual V47 -> V48 upgrade with committed old rows.
        String schema = "version_upgrade";
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("47").load().migrate();
        tx(() -> {
            jdbc.update("insert into version_upgrade.users select * from public.users where id = ?", f.userId());
            jdbc.update("insert into version_upgrade.orders select * from public.orders where id = ?", f.orderId());
            jdbc.update("insert into version_upgrade.payments select * from public.payments where id = ?", f.paymentId());
            for (String status : List.of("OPEN", "RESOLVED")) {
                jdbc.update("""
                        insert into version_upgrade.payment_discrepancies
                        (payment_id, local_status, provider_status, status, first_detected_at,
                         last_detected_at, created_at, updated_at)
                        values (?, 'AUTHORIZED', 'SUCCEEDED', ?, ?, ?, ?, ?)
                        """, f.paymentId(), status, CREATED, CREATED, CREATED, CREATED);
            }
            assertThat(jdbc.queryForObject("""
                    select count(*) from information_schema.columns
                    where table_schema = 'version_upgrade' and table_name = 'payment_discrepancies'
                    and column_name = 'version'
                    """, Integer.class)).isZero();
            return null;
        });
        Flyway upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(upgrade.info().current().getVersion().toString()).isEqualTo("48");
        tx(() -> {
            assertThat(jdbc.queryForList("select status, version from version_upgrade.payment_discrepancies order by status"))
                    .containsExactly(Map.of("status", "OPEN", "version", 0L), Map.of("status", "RESOLVED", "version", 0L));
            Map<String, Object> column = jdbc.queryForMap("""
                    select data_type, is_nullable, column_default from information_schema.columns
                    where table_schema = 'version_upgrade' and table_name = 'payment_discrepancies' and column_name = 'version'
                    """);
            assertThat(column).containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO")
                    .containsEntry("column_default", "0");
            return null;
        });
    }

    @Test
    void hibernateInitializesAndIncrementsVersionForAllThreeBusinessUpdates() {
        Fixture f = fixture(1);
        assertThat(row(f).get("version")).isEqualTo(0L);
        tx(() -> { discrepancies.findById(f.id()).orElseThrow().detectAgain(AUDITED); return null; });
        assertThat(row(f)).containsEntry("version", 1L).containsEntry("detection_count", 2);
        tx(() -> { discrepancies.findById(f.id()).orElseThrow().resolve(AUDITED); return null; });
        assertThat(row(f)).containsEntry("version", 2L).containsEntry("status", "RESOLVED");
        admin.changeHandlingStatus(f.id(), 2L, PaymentDiscrepancyHandlingStatus.CONFIRMED, 113L, "admin");
        assertThat(row(f)).containsEntry("version", 3L).containsEntry("handling_status", "CONFIRMED");
        assertThat(histories(f)).hasSize(1);
    }

    @Test
    void staleAuditCannotOverwriteCommittedAdminChange() throws Exception {
        Fixture f = fixture(1);
        provider(f, PaymentFlowStatus.SUCCEEDED);
        Pause paused = pause("audit", AUDIT_SELECT);
        Future<Failure> stale = submit("audit", () -> audit.auditWithResult(f.paymentId()));
        paused.slot().awaitRead();
        admin.changeHandlingStatus(f.id(), 0L, PaymentDiscrepancyHandlingStatus.CONFIRMED, 113L, "admin");
        Map<String, Object> committed = row(f);
        int winnerPid = paused.pid().get();
        assertThat(tx(() -> jdbc.queryForObject("select pg_backend_pid()", Integer.class))).isNotEqualTo(winnerPid);
        paused.slot().release();
        assertConflict(stale.get(20, TimeUnit.SECONDS), "case1", f);
        assertThat(row(f)).isEqualTo(committed);
        assertThat(row(f)).containsEntry("version", 1L).containsEntry("handling_status", "CONFIRMED")
                .containsEntry("detection_count", 1);
        assertThat(histories(f)).hasSize(1);
    }

    @Test
    void staleAdminRollsBackAlreadyInsertedHistoryAndPreservesResolution() throws Exception {
        Fixture f = fixture(5);
        provider(f, PaymentFlowStatus.REQUIRES_CAPTURE);
        Pause paused = pause("admin", "findById");
        Future<Failure> stale = submit("admin", () -> admin.changeHandlingStatus(
                f.id(), 0L, PaymentDiscrepancyHandlingStatus.CONFIRMED, 113L, "admin"));
        paused.slot().awaitRead();
        audit.auditWithResult(f.paymentId());
        Map<String, Object> committed = row(f);
        assertThat(committed).containsEntry("status", "RESOLVED");
        paused.slot().release();
        assertConflict(stale.get(20, TimeUnit.SECONDS), "case2", f);
        assertThat(row(f)).isEqualTo(committed);
        assertThat(row(f)).containsEntry("version", 1L).containsEntry("detection_count", 5)
                .containsEntry("handling_status", "UNCONFIRMED");
        assertThat(histories(f)).isEmpty();
    }

    @Test
    void staleRedetectionCannotOverwriteCommittedIncrement() throws Exception {
        Fixture f = fixture(5);
        provider(f, PaymentFlowStatus.SUCCEEDED);
        Pause paused = pause("audit", AUDIT_SELECT);
        Future<Failure> stale = submit("audit", () -> audit.auditWithResult(f.paymentId()));
        paused.slot().awaitRead();
        audit.auditWithResult(f.paymentId());
        Map<String, Object> committed = row(f);
        paused.slot().release();
        assertConflict(stale.get(20, TimeUnit.SECONDS), "case3", f);
        assertThat(row(f)).isEqualTo(committed);
        assertThat(row(f)).containsEntry("version", 1L).containsEntry("detection_count", 6)
                .containsEntry("handling_status", "UNCONFIRMED");
        assertThat(histories(f)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentFlowStatus.class, names = {"SUCCEEDED", "REQUIRES_CAPTURE"})
    void formShownBeforeCommittedAuditCannotChangeHandling(PaymentFlowStatus providerStatus) {
        Fixture f = fixture(5);
        // Same read used by the detail GET, completed before the audit starts.
        Long displayedVersion = admin.findById(f.id()).getVersion();
        provider(f, providerStatus);
        audit.auditWithResult(f.paymentId());
        Map<String, Object> committed = row(f);
        assertThat(committed.get("version")).isEqualTo(displayedVersion + 1);
        assertThatThrownBy(() -> admin.changeHandlingStatus(f.id(), displayedVersion,
                PaymentDiscrepancyHandlingStatus.CONFIRMED, 114L, "stale-form"))
                .isInstanceOf(PaymentDiscrepancyConflictException.class);
        assertThat(row(f)).isEqualTo(committed);
        assertThat(histories(f)).isEmpty();
        System.out.printf("FEATURE113 ADMIN stale form: displayedVersion=%d, row=%s, histories=%s%n",
                displayedVersion, row(f), histories(f));
    }

    @Test
    void twoAdministratorsWithSameDisplayedVersionKeepOnlyFirstHistory() {
        Fixture f = fixture(1);
        Long firstVersion = admin.findById(f.id()).getVersion();
        Long secondVersion = admin.findById(f.id()).getVersion();
        assertThat(admin.changeHandlingStatus(f.id(), firstVersion,
                PaymentDiscrepancyHandlingStatus.CONFIRMED, 114L, "winner")).isTrue();
        Map<String, Object> committed = row(f);
        List<Map<String, Object>> history = histories(f);
        // Even a same-status POST must reject the stale form before no-op handling.
        assertThatThrownBy(() -> admin.changeHandlingStatus(f.id(), secondVersion,
                PaymentDiscrepancyHandlingStatus.CONFIRMED, 115L, "loser"))
                .isInstanceOf(PaymentDiscrepancyConflictException.class);
        assertThat(row(f)).isEqualTo(committed);
        assertThat(histories(f)).isEqualTo(history).hasSize(1);
        assertThat(history.getFirst()).containsEntry("changed_by_username", "winner");
    }

    @Test
    void twoAdministratorsRacingAfterVersionComparisonRollBackLoserHistory() throws Exception {
        Fixture f = fixture(1);
        Long displayedVersion = admin.findById(f.id()).getVersion();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger losingPid = new AtomicInteger();
        Future<Failure> stale = submit("admin", () -> {
            // Admin service joins this worker's Tx; compare and history INSERT complete before the pause.
            boolean changed = admin.changeHandlingStatus(f.id(), displayedVersion,
                    PaymentDiscrepancyHandlingStatus.IN_PROGRESS, 115L, "loser");
            assertThat(changed).isTrue();
            assertThat(jdbc.queryForObject("""
                    select count(*) from payment_discrepancy_handling_status_histories
                    where payment_discrepancy_id = ? and changed_by_username = 'loser'
                    """, Integer.class, f.id())).isEqualTo(1);
            losingPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            jdbc.execute("set local lock_timeout = '10s'");
            jdbc.execute("set local statement_timeout = '15s'");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    ready.countDown();
                    try {
                        if (!resume.await(20, TimeUnit.SECONDS)) throw new AssertionError("commit pause timed out");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("commit pause interrupted", failure);
                    }
                }
            });
            return changed;
        });
        try {
            assertThat(ready.await(20, TimeUnit.SECONDS)).as("comparison and history INSERT finished").isTrue();
            assertThat(tx(() -> jdbc.queryForObject("select pg_backend_pid()", Integer.class)))
                    .isNotEqualTo(losingPid.get());
            admin.changeHandlingStatus(f.id(), displayedVersion, PaymentDiscrepancyHandlingStatus.CONFIRMED,
                    114L, "winner");
            Map<String, Object> committed = row(f);
            List<Map<String, Object>> history = histories(f);
            resume.countDown();
            assertConflict(stale.get(20, TimeUnit.SECONDS), "admin-admin", f);
            assertThat(row(f)).isEqualTo(committed).containsEntry("version", 1L)
                    .containsEntry("handling_status", "CONFIRMED");
            assertThat(histories(f)).isEqualTo(history).hasSize(1);
            assertThat(history.getFirst()).containsEntry("changed_by_username", "winner");
        } finally { resume.countDown(); }
    }

    private Pause pause(String role, String method) {
        ReadSlot slot = new ReadSlot(method);
        AtomicInteger pid = new AtomicInteger();
        slot.onRead = result -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.execute("set local lock_timeout = '10s'");
            jdbc.execute("set local statement_timeout = '15s'");
            pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            // afterCommit must remain false if flush/commit fails.
            gate.slots.get(role); // real SELECT has already returned; no repository stubbing.
        };
        gate.slots.put(role, slot);
        return new Pause(slot, pid);
    }

    private Future<Failure> submit(String role, Supplier<?> action) {
        return workers.submit(() -> {
            gate.role.set(role);
            java.util.concurrent.atomic.AtomicBoolean afterCommit = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                // Existing service REQUIRED Tx joins this worker-owned independent Tx.
                tx(() -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() { afterCommit.set(true); }
                    });
                    action.get();
                    return null;
                });
                return new Failure(null, afterCommit.get());
            } catch (RuntimeException failure) {
                return new Failure(failure, afterCommit.get());
            } finally { gate.role.remove(); }
        });
    }

    private void assertConflict(Failure failure, String scenario, Fixture f) {
        assertThat(failure.exception()).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(failure.committed()).isFalse();
        System.out.printf("FEATURE113 VERSION %s: failure=%s; cause=%s; afterCommit=%s; row=%s; histories=%s%n",
                scenario, failure.exception().getClass().getName(), failure.exception().getCause(),
                failure.committed(), row(f), histories(f));
    }

    private Fixture fixture(int count) {
        return tx(() -> {
            String unique = UUID.randomUUID().toString();
            User user = new User();
            user.setUsername("v113-" + unique); user.setPassword("test"); user.setEnabled(true);
            user.setCreatedAt(CREATED); user.setUpdatedAt(CREATED);
            users.saveAndFlush(user);
            Order order = orders.saveAndFlush(new Order(user.getId(), 1000, CREATED, CREATED.plusHours(4)));
            Payment payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, CREATED);
            payment.setProviderPaymentId("pfw_" + unique, CREATED); payment.markAuthorized(CREATED);
            payments.saveAndFlush(payment);
            PaymentDiscrepancy entity = new PaymentDiscrepancy(payment, PaymentStatus.AUTHORIZED,
                    PaymentFlowStatus.SUCCEEDED, CREATED);
            for (int i = 1; i < count; i++) entity.detectAgain(CREATED);
            discrepancies.saveAndFlush(entity);
            return new Fixture(user.getId(), order.getId(), payment.getId(), payment.getProviderPaymentId(), entity.getId());
        });
    }

    private void provider(Fixture f, PaymentFlowStatus status) {
        when(gateway.retrievePaymentFlow(f.providerId())).thenReturn(new PaymentFlowState(f.providerId(), status, null, null));
    }
    private Map<String, Object> row(Fixture f) {
        return tx(() -> jdbc.queryForMap("select * from payment_discrepancies where id = ?", f.id()));
    }
    private List<Map<String, Object>> histories(Fixture f) {
        return tx(() -> jdbc.queryForList("select * from payment_discrepancy_handling_status_histories where payment_discrepancy_id = ?", f.id()));
    }
    private <T> T tx(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }
    private record Fixture(Long userId, Long orderId, Long paymentId, String providerId, Long id) {}
    private record Pause(ReadSlot slot, AtomicInteger pid) {}
    private record Failure(RuntimeException exception, boolean committed) {}
}
