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
import com.example.ecsite.service.AdminPaymentDiscrepancyReconciliationService;
import com.example.ecsite.config.PaymentDiscrepancyAuditProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.example.ecsite.service.payment.PaymentDiscrepancyConcurrencyPostgresTest.ReadSlot;
import com.example.ecsite.service.payment.PaymentDiscrepancyConcurrencyPostgresTest.SelectGate;

/** Real PostgreSQL failures and retries, with a fresh Tx and Entity on every attempt. */
@DataJpaTest(properties = {
        "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false",
        "spring.flyway.enabled=true", "spring.flyway.clean-disabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never",
        "spring.datasource.hikari.maximum-pool-size=5", "spring.jpa.show-sql=false",
        // Deterministically flush the nonconflicting lower ID before the conflicting higher ID.
        "spring.jpa.properties.hibernate.order_updates=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentDiscrepancyAuditRetryFacade.class, AdminPaymentDiscrepancyReconciliationService.class, PaymentDiscrepancyAuditItemService.class, PaymentDiscrepancyEvaluator.class,
        AdminPaymentDiscrepancyService.class, PaymentDiscrepancyConcurrencyPostgresTest.SynchronizationConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class PaymentDiscrepancyAuditRetryPostgresTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 5, 10, 0);
    private static final LocalDateTime AUDITED = LocalDateTime.of(2026, 10, 6, 12, 0);
    private static final String AUDIT_SELECT = "findByPaymentIdAndLocalStatusAndProviderStatusAndStatus";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature113_retry").withUsername("feature113").withPassword("feature113-test");

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
    @Autowired private PaymentTransactionRepository paymentTransactions;
    @Autowired private OrderRepository orders;
    @Autowired private UserRepository users;
    @Autowired private PaymentDiscrepancyAuditRetryFacade audit;
    @Autowired private AdminPaymentDiscrepancyReconciliationService reconciliation;
    @Autowired private EntityManager entityManager;
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
        try {
            verify(gateway, never()).prepareAuthorization(org.mockito.ArgumentMatchers.any());
            verify(gateway, never()).retrieveAuthorization(org.mockito.ArgumentMatchers.any());
            verify(gateway, never()).capture(org.mockito.ArgumentMatchers.any());
            verify(gateway, never()).cancelAuthorization(org.mockito.ArgumentMatchers.any());
        } finally {
            gate.releaseAll();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }


    @Test
    void manualReconciliationStopsAfterThreeRolledBackAttempts() throws Exception {
        Fixture f = fixture(1);
        provider(f, PaymentFlowStatus.SUCCEEDED);
        var before = protectedRows(f);
        Collision collision = collideOnEachRead(f, 3);
        Future<Throwable> result = workers.submit(() -> {
            gate.role.set("retry");
            try { reconciliation.reconcile(f.id()); return null; }
            catch (RuntimeException failure) { return failure; }
            finally { gate.role.remove(); }
        });
        assertThat(result.get(20, TimeUnit.SECONDS)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(collision.reads()).hasSize(3);
        assertThat(new HashSet<>(collision.txIds())).hasSize(3);
        assertFreshEntities(collision);
        verify(gateway, times(3)).retrievePaymentFlow(f.providerId());
        // Only the three independently committed competing increments persist.
        assertThat(row(f)).containsEntry("detection_count", 4).containsEntry("version", 3L);
        assertThat(histories(f)).isEmpty();
        assertThat(protectedRows(f)).isEqualTo(before);
        System.out.printf("FEATURE113 RETRY exhausted: attempts=3, GET=3, txIds=%s, row=%s%n", collision.txIds(), row(f));
    }

    @Test
    void retryReadsFreshProviderStateAndUsesIndependentTransactionsEvenWithOuterTx() {
        Fixture f = fixture(1);
        var before = protectedRows(f);
        when(gateway.retrievePaymentFlow(f.providerId()))
                .thenReturn(new PaymentFlowState(f.providerId(), PaymentFlowStatus.SUCCEEDED, null, null),
                        new PaymentFlowState(f.providerId(), PaymentFlowStatus.REQUIRES_CAPTURE, null, null));
        Collision collision = collideOnEachRead(f, 1);
        gate.role.set("retry");
        AtomicBoolean attemptedCommit = new AtomicBoolean();
        try {
            TransactionTemplate outer = new TransactionTemplate(transactionManager);
            outer.execute(status -> {
                long outerId = jdbc.queryForObject("select txid_current()", Long.class);
                PaymentDiscrepancy staleOuter = discrepancies.findById(f.id()).orElseThrow();
                PaymentDiscrepancyAuditResult result = audit.auditWithResult(f.paymentId());
                assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.CONSISTENT);
                assertThat(collision.txIds()).doesNotContain(outerId);
                // Caller retains its own PC; retry committed before returning despite outer rollback.
                assertThat(staleOuter.getStatus()).isEqualTo(PaymentDiscrepancyRecordStatus.OPEN);
                attemptedCommit.set(true);
                status.setRollbackOnly();
                return null;
            });
        } finally { gate.role.remove(); }
        assertThat(attemptedCommit).isTrue();
        verify(gateway, times(2)).retrievePaymentFlow(f.providerId());
        assertThat(row(f)).containsEntry("status", "RESOLVED").containsEntry("detection_count", 2)
                .containsEntry("version", 2L);
        assertThat(protectedRows(f)).isEqualTo(before);
    }

    @Test
    void scheduledBatchContinuesToNextPaymentAfterRetryExhaustion() {
        Fixture first = fixture(1);
        Fixture second = fixture(1);
        var beforeFirst = protectedRows(first);
        var beforeSecond = protectedRows(second);
        provider(first, PaymentFlowStatus.SUCCEEDED);
        provider(second, PaymentFlowStatus.SUCCEEDED);
        Collision collision = collideOnEachRead(first, 3);
        PaymentDiscrepancyAuditService batch = new PaymentDiscrepancyAuditService(payments, audit,
                new PaymentDiscrepancyAuditProperties(true, Duration.ofMinutes(5), 2));
        org.springframework.test.util.ReflectionTestUtils.setField(batch, "lastPaymentId", first.paymentId() - 1);
        gate.role.set("retry");
        try { batch.auditPayments(); } finally { gate.role.remove(); }
        verify(gateway, times(3)).retrievePaymentFlow(first.providerId());
        verify(gateway, times(1)).retrievePaymentFlow(second.providerId());
        assertThat(collision.reads()).hasSize(3);
        assertThat(row(first)).containsEntry("detection_count", 4).containsEntry("version", 3L);
        assertThat(row(second)).containsEntry("detection_count", 2).containsEntry("version", 1L);
        assertThat(protectedRows(first)).isEqualTo(beforeFirst);
        assertThat(protectedRows(second)).isEqualTo(beforeSecond);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FK", "CHECK", "OTHER_UNIQUE"})
    void otherRealPostgresConstraintFailuresAreNotRetried(String kind) {
        Fixture f = fixture(1);
        var before = row(f);
        var protectedBefore = protectedRows(f);
        when(gateway.retrievePaymentFlow(f.providerId())).thenAnswer(invocation -> {
            String sql = switch (kind) {
                case "FK" -> "update payment_discrepancies set payment_id = -1 where id = " + f.id();
                case "CHECK" -> "update payment_discrepancies set detection_count = 0 where id = " + f.id();
                default -> "insert into payment_discrepancies select * from payment_discrepancies where id = " + f.id();
            };
            entityManager.createNativeQuery(sql).executeUpdate();
            throw new AssertionError("Constraint must reject SQL");
        });
        String state = switch (kind) { case "FK" -> "23503"; case "CHECK" -> "23514"; default -> "23505"; };
        assertThatThrownBy(() -> audit.auditWithResult(f.paymentId())).satisfies(failure -> {
            ConstraintViolationException constraint = findConstraint(failure);
            assertThat(constraint).isNotNull();
            assertThat(constraint.getSQLState()).isEqualTo(state);
            assertThat(constraint.getConstraintName()).isNotEqualTo("uq_payment_discrepancies_open");
        });
        verify(gateway, times(1)).retrievePaymentFlow(f.providerId());
        assertThat(row(f)).isEqualTo(before);
        assertThat(protectedRows(f)).isEqualTo(protectedBefore);
        assertThat(histories(f)).isEmpty();
    }

    @Test
    void gatewayFailureIsNotRetriedAndWritesNothing() {
        Fixture f = fixture(1);
        var before = row(f);
        var protectedBefore = protectedRows(f);
        var failure = new com.example.ecsite.payment.PaymentGatewayException("API unavailable");
        when(gateway.retrievePaymentFlow(f.providerId())).thenThrow(failure);
        assertThatThrownBy(() -> audit.auditWithResult(f.paymentId())).isSameAs(failure);
        verify(gateway, times(1)).retrievePaymentFlow(f.providerId());
        assertThat(row(f)).isEqualTo(before);
        assertThat(protectedRows(f)).isEqualTo(protectedBefore);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 60})
    void auditKeepsSameOrNewerDetectionTimeAfterAdminConflict(int minutesAhead) {
        Fixture f = fixture(5);
        LocalDateTime latestDetection = AUDITED.plusMinutes(minutesAhead);
        tx(() -> { discrepancies.findById(f.id()).orElseThrow().detectAgain(latestDetection); return null; });
        var protectedBefore = protectedRows(f);
        provider(f, PaymentFlowStatus.SUCCEEDED);
        var failedCompletion = new AtomicInteger(-1);
        boundaryRead(AUDIT_SELECT, result -> {
            observeCompletion(failedCompletion);
            competing(() -> admin.changeHandlingStatus(f.id(), 1L,
                    PaymentDiscrepancyHandlingStatus.CONFIRMED, 115L, "winner"));
        });
        PaymentDiscrepancyAuditResult result = boundaryAudit(f);
        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.INCONSISTENT);
        assertThat(failedCompletion).hasValue(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(row(f)).containsEntry("detection_count", 7).containsEntry("version", 3L)
                .containsEntry("handling_status", "CONFIRMED")
                .containsEntry("first_detected_at", java.sql.Timestamp.valueOf(CREATED))
                .containsEntry("last_detected_at", java.sql.Timestamp.valueOf(latestDetection))
                .containsEntry("updated_at", java.sql.Timestamp.valueOf(latestDetection));
        verify(gateway, times(2)).retrievePaymentFlow(f.providerId());
        assertThat(histories(f)).hasSize(1);
        assertThat(protectedRows(f)).isEqualTo(protectedBefore);
    }

    @Test
    void retryConsistentDoesNotCountRolledBackDetection() {
        Fixture f = fixture(5);
        var protectedBefore = protectedRows(f);
        when(gateway.retrievePaymentFlow(f.providerId()))
                .thenReturn(new PaymentFlowState(f.providerId(), PaymentFlowStatus.SUCCEEDED, null, null),
                        new PaymentFlowState(f.providerId(), PaymentFlowStatus.REQUIRES_CAPTURE, null, null));
        var failedCompletion = new AtomicInteger(-1);
        boundaryRead(AUDIT_SELECT, result -> {
            observeCompletion(failedCompletion);
            competing(() -> admin.changeHandlingStatus(f.id(), 0L,
                    PaymentDiscrepancyHandlingStatus.CONFIRMED, 115L, "winner"));
        });
        PaymentDiscrepancyAuditResult result = boundaryAudit(f);
        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.CONSISTENT);
        assertThat(failedCompletion).hasValue(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(row(f)).containsEntry("detection_count", 5).containsEntry("status", "RESOLVED")
                .containsEntry("resolved_at", java.sql.Timestamp.valueOf(AUDITED))
                .containsEntry("version", 2L).containsEntry("handling_status", "CONFIRMED");
        verify(gateway, times(2)).retrievePaymentFlow(f.providerId());
        assertThat(protectedRows(f)).isEqualTo(protectedBefore);
        assertThat(histories(f)).hasSize(1);
        System.out.printf("FEATURE113 BOUNDARY CONSISTENT: rollback=%d, count=%s, GET=2%n",
                failedCompletion.get(), row(f).get("detection_count"));
    }

    @Test
    void retrySkippedRereadsPendingTransactionsAndDoesNotCountDetection() {
        Fixture f = fixture(5);
        provider(f, PaymentFlowStatus.SUCCEEDED);
        var failedCompletion = new AtomicInteger(-1);
        var committedBusinessRows = new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
        boundaryRead(AUDIT_SELECT, result -> {
            observeCompletion(failedCompletion);
            competing(() -> tx(() -> {
                admin.changeHandlingStatus(f.id(), 0L, PaymentDiscrepancyHandlingStatus.CONFIRMED, 115L, "winner");
                // Test fixture: another operation becomes PENDING before the retry starts.
                paymentTransactions.saveAndFlush(new PaymentTransaction(payments.findById(f.paymentId()).orElseThrow(),
                        PaymentTransactionType.AUTHORIZE, 1000, 0, UUID.randomUUID().toString(), CREATED));
                return null;
            }));
            committedBusinessRows.set(protectedRows(f));
        });
        PaymentDiscrepancyAuditResult result = boundaryAudit(f);
        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.SKIPPED);
        assertThat(result.skipReason()).isEqualTo(PaymentDiscrepancyAuditResult.SkipReason.PENDING_TRANSACTION);
        assertThat(result.discrepancyIds()).isEmpty();
        assertThat(failedCompletion).hasValue(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(row(f)).containsEntry("detection_count", 5).containsEntry("version", 1L)
                .containsEntry("status", "OPEN").containsEntry("resolved_at", null);
        // PENDING check precedes GET, so the second attempt makes no external call.
        verify(gateway, times(1)).retrievePaymentFlow(f.providerId());
        assertThat(histories(f)).hasSize(1);
        assertThat(protectedRows(f)).isEqualTo(committedBusinessRows.get());
        System.out.printf("FEATURE113 BOUNDARY SKIPPED: rollback=%d, count=%s, GET=1%n",
                failedCompletion.get(), row(f).get("detection_count"));
    }

    @Test
    void resolvingMultipleRowsRollsBackEarlierSqlUpdateAndReevaluatesAllRows() {
        Fixture f = fixture(5);
        Long secondId = tx(() -> discrepancies.saveAndFlush(new PaymentDiscrepancy(
                payments.findById(f.paymentId()).orElseThrow(), PaymentStatus.CAPTURED,
                PaymentFlowStatus.REQUIRES_CAPTURE, CREATED)).getId());
        provider(f, PaymentFlowStatus.REQUIRES_CAPTURE);
        var protectedBefore = protectedRows(f);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger failedCompletion = new AtomicInteger(-1);
        AtomicBoolean rolledBackRowsObserved = new AtomicBoolean();
        // Sequence increments survive rollback: observe real UPDATE execution without changing business data.
        jdbc.execute("create sequence feature113_resolution_updates");
        jdbc.execute("""
                create function feature113_observe_resolution() returns trigger language plpgsql as $$
                begin perform nextval('feature113_resolution_updates'); return new; end $$
                """);
        try {
            jdbc.execute("create trigger feature113_resolution_probe after update on payment_discrepancies "
                    + "for each row when (old.payment_id = " + f.paymentId()
                    + " and old.status = 'OPEN' and new.status = 'RESOLVED') "
                    + "execute function feature113_observe_resolution()");
            boundaryRead("findByPaymentIdAndStatus", selected -> {
                if (reads.incrementAndGet() == 1) {
                    observeCompletion(failedCompletion);
                    competing(() -> admin.changeHandlingStatus(secondId, 0L,
                            PaymentDiscrepancyHandlingStatus.CONFIRMED, 115L, "winner"));
                } else {
                    assertThat(failedCompletion).hasValue(TransactionSynchronization.STATUS_ROLLED_BACK);
                    List<Map<String, Object>> rows = jdbc.queryForList(
                            "select * from payment_discrepancies where payment_id = ? order by id", f.paymentId());
                    assertThat(rows).hasSize(2).allSatisfy(row -> {
                        assertThat(row).containsEntry("status", "OPEN").containsEntry("resolved_at", null);
                    });
                    assertThat(rows.getFirst()).containsEntry("version", 0L).containsEntry("detection_count", 5);
                    assertThat(rows.getLast()).containsEntry("version", 1L).containsEntry("handling_status", "CONFIRMED");
                    rolledBackRowsObserved.set(true);
                }
                gate.slots.get("boundary").firstRead.set(true);
            });
            PaymentDiscrepancyAuditResult result = boundaryAudit(f);
            assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.CONSISTENT);
            assertThat(result.discrepancyIds()).containsExactlyInAnyOrder(f.id(), secondId);
            assertThat(reads).hasValue(2);
            assertThat(rolledBackRowsObserved).isTrue();
            List<Map<String, Object>> rows = tx(() -> jdbc.queryForList(
                    "select * from payment_discrepancies where payment_id = ? order by id", f.paymentId()));
            assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("status", "RESOLVED")
                    .containsEntry("resolved_at", java.sql.Timestamp.valueOf(AUDITED)));
            assertThat(rows.getFirst()).containsEntry("detection_count", 5).containsEntry("version", 1L);
            assertThat(rows.getLast()).containsEntry("detection_count", 1).containsEntry("version", 2L);
            assertThat(tx(() -> jdbc.queryForObject("select last_value from feature113_resolution_updates", Long.class)))
                    .as("one rolled-back SQL UPDATE plus two committed resolution UPDATEs").isEqualTo(3L);
            verify(gateway, times(2)).retrievePaymentFlow(f.providerId());
            assertThat(protectedRows(f)).isEqualTo(protectedBefore);
            System.out.printf("FEATURE113 BOUNDARY MULTI: attempts=%d, rollback observed=%s, SQL updates=3, rows=%s%n",
                    reads.get(), rolledBackRowsObserved.get(), rows);
        } finally {
            jdbc.execute("drop trigger if exists feature113_resolution_probe on payment_discrepancies");
            jdbc.execute("drop function feature113_observe_resolution()");
            jdbc.execute("drop sequence feature113_resolution_updates");
        }
    }

    private ReadSlot boundaryRead(String method, java.util.function.Consumer<Object> action) {
        ReadSlot slot = new ReadSlot(method);
        slot.release();
        slot.onRead = result -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.execute("set local lock_timeout = '10s'");
            jdbc.execute("set local statement_timeout = '15s'");
            action.accept(result);
        };
        gate.slots.put("boundary", slot);
        return slot;
    }

    private PaymentDiscrepancyAuditResult boundaryAudit(Fixture f) {
        gate.role.set("boundary");
        try { return audit.auditWithResult(f.paymentId()); }
        finally { gate.role.remove(); }
    }

    private void observeCompletion(AtomicInteger completion) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { completion.set(status); }
        });
    }

    private void competing(Supplier<?> action) {
        int readerPid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
        try {
            workers.submit(() -> tx(() -> {
                assertThat(jdbc.queryForObject("select pg_backend_pid()", Integer.class)).isNotEqualTo(readerPid);
                return action.get();
            })).get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("Competing Tx failed, not a successful concurrency reproduction", failure);
        }
    }

    private Collision collideOnEachRead(Fixture f, int limit) {
        List<PaymentDiscrepancy> reads = new ArrayList<>();
        List<Long> txIds = new ArrayList<>();
        ReadSlot slot = new ReadSlot(AUDIT_SELECT);
        slot.release();
        slot.onRead = result -> {
            PaymentDiscrepancy entity = (PaymentDiscrepancy) ((java.util.Optional<?>) result).orElseThrow();
            if (entity.getId().equals(f.id()) && reads.size() < limit) {
                reads.add(entity);
                txIds.add(jdbc.queryForObject("select txid_current()", Long.class));
                int readerPid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                try {
                    workers.submit(() -> tx(() -> {
                        assertThat(jdbc.queryForObject("select pg_backend_pid()", Integer.class)).isNotEqualTo(readerPid);
                        discrepancies.findById(f.id()).orElseThrow().detectAgain(CREATED);
                        return null;
                    })).get(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError("Competing Tx failed, not a successful concurrency reproduction", failure);
                }
            }
            slot.firstRead.set(true);
        };
        gate.slots.put("retry", slot);
        return new Collision(reads, txIds);
    }

    private void assertFreshEntities(Collision collision) {
        for (int i = 0; i < collision.reads().size(); i++) {
            for (int j = i + 1; j < collision.reads().size(); j++) {
                assertThat(collision.reads().get(i)).isNotSameAs(collision.reads().get(j));
            }
        }
    }

    private ConstraintViolationException findConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) return constraint;
        }
        return null;
    }

    private List<List<Map<String, Object>>> protectedRows(Fixture f) {
        return tx(() -> List.of(
                jdbc.queryForList("select * from payments where id = ?", f.paymentId()),
                jdbc.queryForList("select * from orders where id = ?", f.orderId()),
                jdbc.queryForList("select * from payment_transactions where payment_id = ?", f.paymentId())));
    }

    private record Collision(List<PaymentDiscrepancy> reads, List<Long> txIds) {}

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
