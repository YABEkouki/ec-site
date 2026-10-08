package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.aopalliance.intercept.MethodInterceptor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.entity.User;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;
import com.example.ecsite.repository.UserRepository;
import com.example.ecsite.service.AdminPaymentDiscrepancyService;

import jakarta.persistence.OptimisticLockException;

/**
 * Feature113 RED tests: assert the desired behavior, never the current lost updates.
 * The repository decorator executes the real SELECT before pausing. It changes no
 * return values and performs no writes, retries, or exception translation.
 */
@DataJpaTest(properties = {
        "app.payment.reconciliation.enabled=false",
        "app.payment.discrepancy-audit.enabled=false",
        "spring.flyway.enabled=true",
        "spring.flyway.clean-disabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentDiscrepancyAuditRetryFacade.class, PaymentDiscrepancyAuditItemService.class, PaymentDiscrepancyEvaluator.class,
        AdminPaymentDiscrepancyService.class, PaymentDiscrepancyConcurrencyPostgresTest.SynchronizationConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class PaymentDiscrepancyConcurrencyPostgresTest {

    private static final int TIMEOUT_SECONDS = 20;
    private static final String AUDIT_SELECT = "findByPaymentIdAndLocalStatusAndProviderStatusAndStatus";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 5, 10, 0);
    private static final LocalDateTime AUDITED_AT = LocalDateTime.of(2026, 10, 6, 12, 0);

    // No reuse, fixed port, host volume, or fallback to a local database.
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature113_concurrency")
            .withUsername("feature113")
            .withPassword("feature113-test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired private PaymentDiscrepancyAuditRetryFacade auditService;
    @Autowired private AdminPaymentDiscrepancyService adminService;
    @Autowired private PaymentDiscrepancyRepository discrepancies;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentTransactionRepository transactions;
    @Autowired private OrderRepository orders;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;
    @Autowired private ApplicationContext context;
    @Autowired private PlatformTransactionManager transactionManager;
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
            assertThat(connection.getCatalog()).isEqualTo("feature113_concurrency");
        }
        try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
        }
        flyway.validate();
        assertThat(flyway.info().all()).isNotEmpty().allSatisfy(info ->
                assertThat(info.getState()).as(info.getScript()).isEqualTo(MigrationState.SUCCESS));
        assertThat(context.getBeansOfType(PaymentDiscrepancyAuditScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentReconciliationScheduler.class)).isEmpty();
        assertThat(inTransaction(() -> jdbc.queryForObject("show transaction_isolation", String.class)))
                .isEqualTo("read committed");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        gate.releaseAll();
        workers.shutdownNow();
        assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("workers terminated").isTrue();
        verify(gateway, never()).prepareAuthorization(any());
        verify(gateway, never()).retrieveAuthorization(any());
        verify(gateway, never()).capture(any());
        verify(gateway, never()).cancelAuthorization(any());
    }

    @Test
    void auditPreservesCommittedAdminHandlingChangeAndHistory() throws Exception {
        Fixture fixture = fixture(true, 1);
        ProtectedRows before = protectedRows(fixture);
        providerReturns(fixture, PaymentFlowStatus.SUCCEEDED);
        ReadSlot audit = pauseAfterSelect("audit", AUDIT_SELECT);
        ReadSlot admin = pauseAfterSelect("admin", "findById");

        Future<Outcome> auditing = submit("audit", () -> auditService.auditWithResult(fixture.paymentId()));
        audit.awaitRead();
        Future<Outcome> changing = submit("admin", () -> changeHandling(fixture));
        admin.awaitRead();
        assertIndependentReads(audit, admin, 1);
        admin.release();
        assertSucceeded(completed(changing)); // Future completion includes transaction commit.
        Map<String, Object> committedAdmin = discrepancyRow(fixture);
        assertThat(committedAdmin.get("handling_status")).isEqualTo("CONFIRMED");
        assertThat(historyRows(fixture)).hasSize(1);
        audit.release();
        Outcome result = completed(auditing);
        assertSucceeded(result);

        Map<String, Object> row = discrepancyRow(fixture);
        List<Map<String, Object>> history = historyRows(fixture);
        evidence("case1", audit, admin, row, history, result);
        verify(gateway, times(2)).retrievePaymentFlow(fixture.providerId());
        assertThat(protectedRows(fixture)).isEqualTo(before);
        assertSoftly(soft -> {
            soft.assertThat(row.get("handling_status")).isEqualTo("CONFIRMED");
            soft.assertThat(row.get("handling_status_updated_at")).isEqualTo(committedAdmin.get("handling_status_updated_at"));
            soft.assertThat(row.get("detection_count")).isEqualTo(2);
            soft.assertThat(history).hasSize(1);
            soft.assertThat(history.getFirst().get("from_status")).isEqualTo("UNCONFIRMED");
            soft.assertThat(history.getFirst().get("to_status")).isEqualTo("CONFIRMED");
        });
    }

    @Test
    void adminConflictPreservesCommittedResolutionAndLeavesNoHistory() throws Exception {
        Fixture fixture = fixture(true, 5);
        ProtectedRows before = protectedRows(fixture);
        providerReturns(fixture, PaymentFlowStatus.REQUIRES_CAPTURE);
        ReadSlot admin = pauseAfterSelect("admin", "findById");
        ReadSlot audit = pauseAfterSelect("audit", "findByPaymentIdAndStatus");

        Future<Outcome> changing = submit("admin", () -> changeHandling(fixture));
        admin.awaitRead();
        Future<Outcome> auditing = submit("audit", () -> auditService.auditWithResult(fixture.paymentId()));
        audit.awaitRead();
        assertIndependentReads(admin, audit, 5);
        audit.release();
        assertSucceeded(completed(auditing));
        assertThat(discrepancyRow(fixture).get("status")).isEqualTo("RESOLVED");
        admin.release();
        Outcome result = completed(changing);
        assertKnownConcurrencyFailureOrSuccess(result);

        Map<String, Object> row = discrepancyRow(fixture);
        List<Map<String, Object>> history = historyRows(fixture);
        evidence("case2", admin, audit, row, history, result);
        verify(gateway, times(1)).retrievePaymentFlow(fixture.providerId());
        assertThat(protectedRows(fixture)).isEqualTo(before);
        assertSoftly(soft -> {
            soft.assertThat(isOptimisticConflict(result.failure())).as("admin reports optimistic conflict").isTrue();
            soft.assertThat(row.get("status")).isEqualTo("RESOLVED");
            soft.assertThat(row.get("resolved_at")).isEqualTo(java.sql.Timestamp.valueOf(AUDITED_AT));
            soft.assertThat(row.get("detection_count")).isEqualTo(5);
            soft.assertThat(row.get("handling_status")).isEqualTo("UNCONFIRMED");
            soft.assertThat(history).isEmpty();
        });
    }

    @Test
    void twoSuccessfulRedetectionsIncrementFiveToSeven() throws Exception {
        Fixture fixture = fixture(true, 5);
        ProtectedRows before = protectedRows(fixture);
        providerReturns(fixture, PaymentFlowStatus.SUCCEEDED);
        ReadSlot first = pauseAfterSelect("first", AUDIT_SELECT);
        ReadSlot second = pauseAfterSelect("second", AUDIT_SELECT);

        Future<Outcome> firstAudit = submit("first", () -> auditService.auditWithResult(fixture.paymentId()));
        Future<Outcome> secondAudit = submit("second", () -> auditService.auditWithResult(fixture.paymentId()));
        first.awaitRead();
        second.awaitRead();
        assertIndependentReads(first, second, 5);
        first.release();
        assertSucceeded(completed(firstAudit));
        assertThat(discrepancyRow(fixture).get("detection_count")).isEqualTo(6);
        second.release();
        Outcome result = completed(secondAudit);
        assertSucceeded(result);

        Map<String, Object> row = discrepancyRow(fixture);
        evidence("case3", first, second, row, historyRows(fixture), result);
        verify(gateway, times(3)).retrievePaymentFlow(fixture.providerId());
        assertThat(protectedRows(fixture)).isEqualTo(before);
        assertSoftly(soft -> {
            soft.assertThat(row.get("detection_count")).isEqualTo(7);
            soft.assertThat(row.get("handling_status")).isEqualTo("UNCONFIRMED");
            soft.assertThat(row.get("last_detected_at")).isEqualTo(java.sql.Timestamp.valueOf(AUDITED_AT));
            soft.assertThat(historyRows(fixture)).isEmpty();
        });
    }

    @Test
    void concurrentInsertRecoversOnlyOpenUniqueConflictAndCountsBothAudits() throws Exception {
        Fixture fixture = fixture(false, 0);
        ProtectedRows before = protectedRows(fixture);
        providerReturns(fixture, PaymentFlowStatus.SUCCEEDED);
        ReadSlot first = pauseAfterSelect("first", AUDIT_SELECT);
        ReadSlot second = pauseAfterSelect("second", AUDIT_SELECT);

        Future<Outcome> firstAudit = submit("first", () -> auditService.auditWithResult(fixture.paymentId()));
        Future<Outcome> secondAudit = submit("second", () -> auditService.auditWithResult(fixture.paymentId()));
        first.awaitRead();
        second.awaitRead();
        assertThat(first.snapshot.present()).isFalse();
        assertThat(second.snapshot.present()).isFalse();
        assertThat(first.snapshot.backendPid()).isNotEqualTo(second.snapshot.backendPid());
        gate.holdFirstInsert.set(true);
        // Do not await both commits at a barrier: the unique index can make INSERT wait.
        first.release();
        await(gate.inserted, "first INSERT completed before commit");
        second.release();
        awaitUniqueIndexWait(first.snapshot.backendPid(), second.snapshot.backendPid());
        gate.commitInsert.countDown();
        Outcome firstResult = completed(firstAudit);
        assertSucceeded(firstResult);
        Outcome secondResult = completed(secondAudit);
        assertKnownConcurrencyFailureOrSuccess(secondResult);
        if (secondResult.failure() != null) {
            assertThat(sqlState(secondResult.failure())).isEqualTo("23505");
            assertThat(constraintName(secondResult.failure())).isEqualTo("uq_payment_discrepancies_open");
        }

        List<Map<String, Object>> rows = discrepancyRows(fixture);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        evidence("case4", first, second, row, historyRows(fixture), secondResult);
        verify(gateway, times(3)).retrievePaymentFlow(fixture.providerId());
        assertThat(protectedRows(fixture)).isEqualTo(before);
        assertSoftly(soft -> {
            soft.assertThat(secondResult.failure()).as("both audits commit successfully after recovery").isNull();
            soft.assertThat(row.get("status")).isEqualTo("OPEN");
            soft.assertThat(row.get("detection_count")).isEqualTo(2);
            soft.assertThat(row.get("handling_status")).isEqualTo("UNCONFIRMED");
            if (secondResult.failure() == null) {
                soft.assertThat(((PaymentDiscrepancyAuditResult) secondResult.value()).discrepancyIds())
                        .isEqualTo(((PaymentDiscrepancyAuditResult) firstResult.value()).discrepancyIds());
            }
        });
    }

    private boolean changeHandling(Fixture fixture) {
        return adminService.changeHandlingStatus(fixture.discrepancyId(), 0L,
                PaymentDiscrepancyHandlingStatus.CONFIRMED, 113L, "feature113-admin");
    }

    private void awaitUniqueIndexWait(int blockerPid, int waitingPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        CountDownLatch pollInterval = new CountDownLatch(1);
        while (System.nanoTime() < deadline) {
            boolean blocked = inTransaction(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from pg_stat_activity
                    where pid = ? and wait_event_type = 'Lock'
                    and ? = any(pg_blocking_pids(pid)))
                    """, Boolean.class, waitingPid, blockerPid)));
            if (blocked) {
                System.out.printf("FEATURE113 case4: INSERT pid=%d waits for uncommitted unique key held by pid=%d%n",
                        waitingPid, blockerPid);
                return;
            }
            pollInterval.await(25, TimeUnit.MILLISECONDS);
        }
        throw new AssertionError("Expected PostgreSQL unique-key lock wait was not observed");
    }

    private ReadSlot pauseAfterSelect(String role, String method) {
        ReadSlot slot = new ReadSlot(method);
        slot.onRead = result -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            // SET LOCAL bounds DB waits without changing transaction/connection ownership.
            jdbc.execute("set local lock_timeout = '10s'");
            jdbc.execute("set local statement_timeout = '15s'");
            int pid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
            PaymentDiscrepancy entity = result instanceof Optional<?> optional
                    ? (PaymentDiscrepancy) optional.orElse(null)
                    : (PaymentDiscrepancy) ((List<?>) result).getFirst();
            slot.snapshot = new ReadSnapshot(pid, entity != null,
                    entity == null ? null : entity.getDetectionCount(),
                    entity == null ? null : entity.getStatus().name(),
                    entity == null ? null : entity.getHandlingStatus().name());
        };
        gate.slots.put(role, slot);
        return slot;
    }

    private void assertIndependentReads(ReadSlot first, ReadSlot second, int count) {
        assertThat(first.snapshot.backendPid()).isNotEqualTo(second.snapshot.backendPid());
        for (ReadSlot slot : List.of(first, second)) {
            assertThat(slot.snapshot.present()).isTrue();
            assertThat(slot.snapshot.count()).isEqualTo(count);
            assertThat(slot.snapshot.status()).isEqualTo("OPEN");
            assertThat(slot.snapshot.handling()).isEqualTo("UNCONFIRMED");
        }
    }

    private Future<Outcome> submit(String role, Supplier<?> action) {
        return workers.submit(() -> {
            gate.role.set(role);
            try {
                return new Outcome(action.get(), null);
            } catch (RuntimeException failure) {
                return new Outcome(null, failure);
            } finally {
                gate.role.remove();
            }
        });
    }

    private Outcome completed(Future<Outcome> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void assertSucceeded(Outcome outcome) {
        assertThat(outcome.failure()).as("worker must commit, not timeout or fail infrastructure").isNull();
    }

    private void assertKnownConcurrencyFailureOrSuccess(Outcome outcome) {
        if (outcome.failure() != null) {
            assertThat(isOptimisticConflict(outcome.failure())
                    || ("23505".equals(sqlState(outcome.failure()))
                    && "uq_payment_discrepancies_open".equals(constraintName(outcome.failure()))))
                    .as("unexpected worker failure: %s", outcome.failure()).isTrue();
        }
    }

    private static boolean isOptimisticConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException || cause instanceof OptimisticLockException) return true;
        }
        return false;
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    private static String constraintName(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) return constraint.getConstraintName();
        }
        return null;
    }

    private void evidence(String scenario, ReadSlot first, ReadSlot second, Map<String, Object> row,
            List<Map<String, Object>> history, Outcome outcome) {
        System.out.printf("FEATURE113 %s: reads=%s / %s; committedRow=%s; history=%s; failure=%s; SQLSTATE=%s; constraint=%s%n",
                scenario, first.snapshot, second.snapshot, row, history, outcome.failure(),
                sqlState(outcome.failure()), constraintName(outcome.failure()));
    }

    private Fixture fixture(boolean withDiscrepancy, int count) {
        return inTransaction(() -> {
            String unique = UUID.randomUUID().toString();
            User user = new User();
            user.setUsername("f113-" + unique);
            user.setPassword("test-password");
            user.setEnabled(true);
            user.setCreatedAt(CREATED_AT);
            user.setUpdatedAt(CREATED_AT);
            users.saveAndFlush(user);
            Order order = orders.saveAndFlush(new Order(user.getId(), 1000, CREATED_AT, CREATED_AT.plusHours(4)));
            Payment payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, CREATED_AT);
            payment.setProviderPaymentId("pfw_" + unique, CREATED_AT);
            payment.markAuthorized(CREATED_AT);
            payments.saveAndFlush(payment);
            PaymentTransaction transaction = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE,
                    1000, 0, unique, CREATED_AT);
            transaction.markSucceeded("txn_" + unique, CREATED_AT);
            transactions.saveAndFlush(transaction);
            Long id = null;
            if (withDiscrepancy) {
                PaymentDiscrepancy discrepancy = new PaymentDiscrepancy(payment, PaymentStatus.AUTHORIZED,
                        PaymentFlowStatus.SUCCEEDED, CREATED_AT);
                for (int i = 1; i < count; i++) discrepancy.detectAgain(CREATED_AT);
                id = discrepancies.saveAndFlush(discrepancy).getId();
            }
            return new Fixture(order.getId(), payment.getId(), payment.getProviderPaymentId(), id);
        });
    }

    private void providerReturns(Fixture fixture, PaymentFlowStatus status) {
        when(gateway.retrievePaymentFlow(fixture.providerId()))
                .thenReturn(new PaymentFlowState(fixture.providerId(), status, null, null));
    }

    private Map<String, Object> discrepancyRow(Fixture fixture) {
        List<Map<String, Object>> rows = discrepancyRows(fixture);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Map<String, Object>> discrepancyRows(Fixture fixture) {
        return inTransaction(() -> jdbc.queryForList(
                "select * from payment_discrepancies where payment_id = ? order by id", fixture.paymentId()));
    }

    private List<Map<String, Object>> historyRows(Fixture fixture) {
        return inTransaction(() -> jdbc.queryForList("""
                select h.* from payment_discrepancy_handling_status_histories h
                join payment_discrepancies d on d.id = h.payment_discrepancy_id
                where d.payment_id = ? order by h.id
                """, fixture.paymentId()));
    }

    private ProtectedRows protectedRows(Fixture fixture) {
        return inTransaction(() -> new ProtectedRows(
                jdbc.queryForList("select * from orders where id = ?", fixture.orderId()),
                jdbc.queryForList("select * from payments where id = ?", fixture.paymentId()),
                jdbc.queryForList("select * from payment_transactions where payment_id = ? order by id", fixture.paymentId())));
    }

    private <T> T inTransaction(Supplier<T> action) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> action.get());
    }

    private record Fixture(Long orderId, Long paymentId, String providerId, Long discrepancyId) {}
    private record ProtectedRows(List<Map<String, Object>> orders, List<Map<String, Object>> payments,
            List<Map<String, Object>> transactions) {}
    private record Outcome(Object value, RuntimeException failure) {}
    private record ReadSnapshot(int backendPid, boolean present, Integer count, String status, String handling) {}

    static final class ReadSlot {
        final String method;
        final AtomicBoolean firstRead = new AtomicBoolean(true);
        final CountDownLatch read = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        volatile ReadSnapshot snapshot;
        Consumer<Object> onRead;

        ReadSlot(String method) { this.method = method; }
        void awaitRead() { await(read, "real SELECT completed: " + method); }
        void release() { proceed.countDown(); }
    }

    static final class SelectGate {
        final ThreadLocal<String> role = new ThreadLocal<>();
        final Map<String, ReadSlot> slots = new ConcurrentHashMap<>();
        final AtomicBoolean holdFirstInsert = new AtomicBoolean();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commitInsert = new CountDownLatch(1);

        void afterSelect(String method, Object result) {
            if ("save".equals(method) && "first".equals(role.get())
                    && holdFirstInsert.compareAndSet(true, false)) {
                inserted.countDown();
                await(commitInsert, "commit first INSERT after observing second INSERT wait");
            }
            String currentRole = role.get();
            ReadSlot slot = currentRole == null ? null : slots.get(currentRole);
            if (slot != null && slot.method.equals(method) && slot.firstRead.compareAndSet(true, false)) {
                slot.onRead.accept(result);
                slot.read.countDown();
                await(slot.proceed, "release after SELECT: " + currentRole);
            }
        }

        void releaseAll() { slots.values().forEach(ReadSlot::release); commitInsert.countDown(); }
        void reset() {
            releaseAll();
            slots.clear();
            holdFirstInsert.set(false);
            inserted = new CountDownLatch(1);
            commitInsert = new CountDownLatch(1);
        }
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Synchronization timeout, not a concurrency reproduction: " + description);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Synchronization interrupted: " + description, failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SynchronizationConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-06T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
        }

        @Bean
        static SelectGate selectGate() { return new SelectGate(); }

        @Bean
        static BeanPostProcessor realRepositorySelectSynchronization(SelectGate gate) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof PaymentDiscrepancyRepository)) return bean;
                    ProxyFactory proxy = new ProxyFactory();
                    proxy.setTarget(bean);
                    proxy.setInterfaces(PaymentDiscrepancyRepository.class);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        Object result = invocation.proceed();
                        gate.afterSelect(invocation.getMethod().getName(), result);
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }
    }
}
