package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatus;
import com.example.ecsite.entity.PaymentDiscrepancyHandlingStatusHistory;
import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.entity.User;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.PaymentDiscrepancyHandlingStatusHistoryRepository;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;
import com.example.ecsite.repository.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest(properties = {
        "app.payment.reconciliation.enabled=false",
        "app.payment.discrepancy-audit.enabled=false",
        "spring.flyway.enabled=true",
        "spring.flyway.clean-disabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentDiscrepancyAuditItemService.class, PaymentDiscrepancyEvaluator.class,
        PaymentDiscrepancyAuditItemServicePostgresTest.ClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class PaymentDiscrepancyAuditItemServicePostgresTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 5, 10, 0);
    private static final LocalDateTime AUDITED_AT = LocalDateTime.of(2026, 10, 6, 12, 0);

    // No fixed port, host volume, reuse, or fallback to a local database.
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature112_audit")
            .withUsername("feature112")
            .withPassword("feature112-test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired private PaymentDiscrepancyAuditItemService service;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentTransactionRepository transactions;
    @Autowired private PaymentDiscrepancyRepository discrepancies;
    @Autowired private PaymentDiscrepancyHandlingStatusHistoryRepository histories;
    @Autowired private OrderRepository orders;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;
    @Autowired private ApplicationContext context;
    @MockitoBean private PaymentGateway gateway;

    @BeforeEach
    void verifiesIsolatedDatabaseAndDisabledSchedulers() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo("feature112_audit");
        }
        try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
        }
        flyway.validate();
        var migrationInfo = flyway.info();
        assertThat(migrationInfo.all()).isNotEmpty().allSatisfy(migration ->
                assertThat(migration.getState()).as("migration %s", migration.getScript())
                        .isEqualTo(MigrationState.SUCCESS));
        assertThat(migrationInfo.applied()).extracting(MigrationInfo::getScript)
                .containsExactlyInAnyOrderElementsOf(List.of(migrationInfo.all()).stream()
                        .map(MigrationInfo::getScript).toList());
        assertThat(context.getBeansOfType(PaymentReconciliationScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentDiscrepancyAuditScheduler.class)).isEmpty();
    }

    @AfterEach
    void neverCallsPaymentOperations() {
        verify(gateway, never()).prepareAuthorization(any());
        verify(gateway, never()).retrieveAuthorization(any());
        verify(gateway, never()).capture(any());
        verify(gateway, never()).cancelAuthorization(any());
    }

    @Test
    void insertsMismatchAndReturnsCommittedGeneratedId() {
        Fixture fixture = fixture(PaymentStatus.AUTHORIZED, false, false);
        State before = state(fixture);
        providerReturns(fixture, PaymentFlowStatus.SUCCEEDED);

        PaymentDiscrepancyAuditResult result = service.auditWithResult(fixture.paymentId());

        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.INCONSISTENT);
        assertThat(result.providerStatus()).isEqualTo(PaymentFlowStatus.SUCCEEDED);
        assertThat(result.discrepancyIds()).hasSize(1);
        Long id = result.discrepancyIds().getFirst();
        assertThat(id).isPositive();
        inTransaction(() -> {
            PaymentDiscrepancy saved = discrepancies.findById(id).orElseThrow();
            assertThat(saved.getPayment().getId()).isEqualTo(fixture.paymentId());
            assertThat(saved.getStatus()).isEqualTo(PaymentDiscrepancyRecordStatus.OPEN);
            assertThat(saved.getLocalStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(saved.getProviderStatus()).isEqualTo(PaymentFlowStatus.SUCCEEDED);
            assertThat(saved.getDetectionCount()).isEqualTo(1);
            assertThat(saved.getFirstDetectedAt()).isEqualTo(AUDITED_AT);
            return null;
        });
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void detectsSameMismatchAgainWithoutCreatingAnotherRecord() {
        Fixture fixture = fixture(PaymentStatus.AUTHORIZED, false, true);
        State before = state(fixture);
        providerReturns(fixture, PaymentFlowStatus.SUCCEEDED);

        PaymentDiscrepancyAuditResult result = service.auditWithResult(fixture.paymentId());

        assertThat(result.discrepancyIds()).containsExactly(fixture.discrepancyId());
        inTransaction(() -> {
            List<PaymentDiscrepancy> rows = discrepancies.findByPaymentIdAndStatus(
                    fixture.paymentId(), PaymentDiscrepancyRecordStatus.OPEN);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getDetectionCount()).isEqualTo(2);
            assertThat(rows.getFirst().getFirstDetectedAt()).isEqualTo(CREATED_AT);
            assertThat(rows.getFirst().getLastDetectedAt()).isEqualTo(AUDITED_AT);
            assertThat(rows.getFirst().getHandlingStatus()).isEqualTo(PaymentDiscrepancyHandlingStatus.IN_PROGRESS);
            assertThat(rows.getFirst().getHandlingStatusUpdatedAt()).isEqualTo(CREATED_AT);
            return null;
        });
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void resolvesAllOpenRecordsWithoutCompletingHandlingOrChangingHistory() {
        Fixture fixture = fixture(PaymentStatus.AUTHORIZED, false, true);
        Long secondId = inTransaction(() -> discrepancies.saveAndFlush(new PaymentDiscrepancy(
                payments.findById(fixture.paymentId()).orElseThrow(), PaymentStatus.CAPTURED,
                PaymentFlowStatus.REQUIRES_CAPTURE, CREATED_AT)).getId());
        State before = state(fixture);
        providerReturns(fixture, PaymentFlowStatus.REQUIRES_CAPTURE);

        PaymentDiscrepancyAuditResult result = service.auditWithResult(fixture.paymentId());

        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.CONSISTENT);
        assertThat(result.discrepancyIds()).containsExactlyInAnyOrder(fixture.discrepancyId(), secondId);
        inTransaction(() -> {
            PaymentDiscrepancy first = discrepancies.findById(fixture.discrepancyId()).orElseThrow();
            PaymentDiscrepancy second = discrepancies.findById(secondId).orElseThrow();
            assertThat(first.getStatus()).isEqualTo(PaymentDiscrepancyRecordStatus.RESOLVED);
            assertThat(second.getStatus()).isEqualTo(PaymentDiscrepancyRecordStatus.RESOLVED);
            assertThat(first.getResolvedAt()).isEqualTo(AUDITED_AT);
            assertThat(second.getResolvedAt()).isEqualTo(AUDITED_AT);
            assertThat(first.getHandlingStatus()).isEqualTo(PaymentDiscrepancyHandlingStatus.IN_PROGRESS);
            assertThat(first.getHandlingStatusUpdatedAt()).isEqualTo(CREATED_AT);
            assertThat(second.getHandlingStatus()).isEqualTo(PaymentDiscrepancyHandlingStatus.UNCONFIRMED);
            return null;
        });
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void leavesDatabaseUnchangedWhileProviderIsProcessing() {
        Fixture fixture = fixture(PaymentStatus.PENDING, false, true);
        State before = state(fixture);
        List<Map<String, Object>> recordsBefore = discrepancyRows(fixture);
        providerReturns(fixture, PaymentFlowStatus.PROCESSING);

        PaymentDiscrepancyAuditResult result = service.auditWithResult(fixture.paymentId());

        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.IN_PROGRESS);
        assertThat(result.discrepancyIds()).isEmpty();
        assertThat(discrepancyRows(fixture)).isEqualTo(recordsBefore);
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void skipsPendingTransactionWithoutQueryingProvider() {
        Fixture fixture = fixture(PaymentStatus.AUTHORIZED, true, true);
        State before = state(fixture);
        List<Map<String, Object>> recordsBefore = discrepancyRows(fixture);

        PaymentDiscrepancyAuditResult result = service.auditWithResult(fixture.paymentId());

        assertThat(result.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.SKIPPED);
        assertThat(result.skipReason()).isEqualTo(PaymentDiscrepancyAuditResult.SkipReason.PENDING_TRANSACTION);
        verifyNoInteractions(gateway);
        assertThat(discrepancyRows(fixture)).isEqualTo(recordsBefore);
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void preservesDatabaseWhenProviderThrows() {
        Fixture fixture = fixture(PaymentStatus.AUTHORIZED, false, true);
        State before = state(fixture);
        List<Map<String, Object>> recordsBefore = discrepancyRows(fixture);
        PaymentGatewayException failure = new PaymentGatewayException("test API failure");
        when(gateway.retrievePaymentFlow(fixture.providerId())).thenThrow(failure);

        assertThatThrownBy(() -> service.auditWithResult(fixture.paymentId())).isSameAs(failure);

        assertThat(discrepancyRows(fixture)).isEqualTo(recordsBefore);
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void rollsBackFlushedUpdateWhenBeforeCommitThrows() {
        Fixture fixture = fixture(PaymentStatus.AUTHORIZED, false, true);
        State before = state(fixture);
        List<Map<String, Object>> recordsBefore = discrepancyRows(fixture);
        AtomicBoolean updateFlushed = new AtomicBoolean();
        RuntimeException failure = new IllegalStateException("test failure after SQL update");
        when(gateway.retrievePaymentFlow(fixture.providerId())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            // Inject only at transaction completion; repositories and service remain real.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    entityManager.flush();
                    assertThat(jdbc.queryForObject(
                            "select status from payment_discrepancies where id = ?", String.class,
                            fixture.discrepancyId())).isEqualTo("RESOLVED");
                    updateFlushed.set(true);
                    throw failure;
                }
            });
            return flow(fixture, PaymentFlowStatus.REQUIRES_CAPTURE);
        });

        assertThatThrownBy(() -> service.auditWithResult(fixture.paymentId())).isSameAs(failure);

        assertThat(updateFlushed).isTrue();
        assertThat(discrepancyRows(fixture)).isEqualTo(recordsBefore);
        assertThat(state(fixture)).isEqualTo(before);
    }

    private Fixture fixture(PaymentStatus localStatus, boolean pending, boolean withDiscrepancy) {
        return inTransaction(() -> {
            String unique = UUID.randomUUID().toString();
            User user = new User();
            user.setUsername("f112-" + unique);
            user.setPassword("test-password");
            user.setEnabled(true);
            user.setCreatedAt(CREATED_AT);
            user.setUpdatedAt(CREATED_AT);
            users.saveAndFlush(user);
            Order order = orders.saveAndFlush(new Order(user.getId(), 1000, CREATED_AT, CREATED_AT.plusHours(4)));
            Payment payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, CREATED_AT);
            payment.setProviderPaymentId("pfw_" + unique, CREATED_AT);
            if (localStatus == PaymentStatus.AUTHORIZED) {
                payment.markAuthorized(CREATED_AT);
            }
            payments.saveAndFlush(payment);
            PaymentTransaction transaction = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE,
                    1000, 0, unique, CREATED_AT);
            if (!pending) {
                transaction.markSucceeded("txn_" + unique, CREATED_AT);
            }
            transactions.saveAndFlush(transaction);
            Long discrepancyId = null;
            if (withDiscrepancy) {
                PaymentDiscrepancy discrepancy = new PaymentDiscrepancy(payment, PaymentStatus.AUTHORIZED,
                        PaymentFlowStatus.SUCCEEDED, CREATED_AT);
                discrepancy.changeHandlingStatus(PaymentDiscrepancyHandlingStatus.IN_PROGRESS, CREATED_AT);
                discrepancies.saveAndFlush(discrepancy);
                histories.saveAndFlush(PaymentDiscrepancyHandlingStatusHistory.create(discrepancy,
                        PaymentDiscrepancyHandlingStatus.UNCONFIRMED, PaymentDiscrepancyHandlingStatus.IN_PROGRESS,
                        1L, "test-admin", UUID.randomUUID()));
                discrepancyId = discrepancy.getId();
            }
            return new Fixture(order.getId(), payment.getId(), payment.getProviderPaymentId(), discrepancyId);
        });
    }

    private void providerReturns(Fixture fixture, PaymentFlowStatus status) {
        when(gateway.retrievePaymentFlow(fixture.providerId())).thenReturn(flow(fixture, status));
    }

    private PaymentFlowState flow(Fixture fixture, PaymentFlowStatus status) {
        return new PaymentFlowState(fixture.providerId(), status, null, null);
    }

    private State state(Fixture fixture) {
        return inTransaction(() -> new State(
                jdbc.queryForList("select * from orders where id = ?", fixture.orderId()),
                jdbc.queryForList("select * from payments where id = ?", fixture.paymentId()),
                jdbc.queryForList("select * from payment_transactions where payment_id = ? order by id", fixture.paymentId()),
                jdbc.queryForList("""
                        select h.* from payment_discrepancy_handling_status_histories h
                        join payment_discrepancies d on d.id = h.payment_discrepancy_id
                        where d.payment_id = ? order by h.id
                        """, fixture.paymentId())));
    }

    private List<Map<String, Object>> discrepancyRows(Fixture fixture) {
        return inTransaction(() -> jdbc.queryForList(
                "select * from payment_discrepancies where payment_id = ? order by id", fixture.paymentId()));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private record Fixture(Long orderId, Long paymentId, String providerId, Long discrepancyId) {}
    private record State(List<Map<String, Object>> orders, List<Map<String, Object>> payments,
            List<Map<String, Object>> transactions, List<Map<String, Object>> histories) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-06T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
        }
    }
}
