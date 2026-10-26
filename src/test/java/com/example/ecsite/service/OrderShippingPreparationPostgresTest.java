package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.sql.Connection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.RefreshEvent;
import org.hibernate.event.spi.RefreshContext;
import org.hibernate.event.spi.RefreshEventListener;
import java.util.function.Supplier;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.form.OrderItemChangeForm;
import com.example.ecsite.form.OrderShippingAddressForm;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.pricing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderShippingPreparationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("shipping_preparation_start").withUsername("preparation_test")
            .withPassword("preparation-test-only")
            .withCommand("postgres", "-c", "lock_timeout=20000", "-c", "statement_timeout=25000");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired OrderShippingPreparationService preparation;
    @Autowired OrderService orderService;
    @Autowired PaymentCancellationStartService cancellation;
    @Autowired AdminPaymentCancellationService adminCancellation;
    @MockitoSpyBean OrderRepository orders;
    @MockitoSpyBean PaymentService paymentService;
    @MockitoSpyBean PaymentConsistencyEvaluator evaluator;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxes;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired EntityManager entityManager;
    @MockitoBean PaymentGateway gateway;
    @MockitoBean OrderAmountCalculator calculator;
    private ExecutorService workers;
    private CountDownLatch release;

    @BeforeEach
    void setup() {
        workers = Executors.newFixedThreadPool(2);
        release = new CountDownLatch(1);
        authenticate(7L, "first", "ROLE_ADMIN");
    }

    @AfterEach
    void cleanup() throws Exception {
        release.countDown();
        workers.shutdownNow();
        try {
            assertThat(workers.awaitTermination(25, TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(gateway);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void persistsAllMetadataWithoutChangingOtherState(boolean card) {
        Fixture f = fixture(card);
        var before = state(f);
        LocalDateTime earliest = dbNow();
        var result = preparation.start(f.orderId());
        LocalDateTime latest = dbNow();
        assertThat(result.status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
        assertThat(result.startedAt()).isBetween(earliest, latest);
        assertThat(result.startedAt().getNano() % 1000).isZero();
        assertThat(result.startedByAccountId()).isEqualTo(7L);
        assertThat(result.startedByUsername()).isEqualTo("first");
        assertStored(f, result);
        assertThat(withoutPreparation(state(f))).isEqualTo(withoutPreparation(before));
        assertThat(orderRow(f)).containsEntry("status", card ? "ORDERED" : "PAID")
                .containsEntry("shipment_capture_payment_id", null);
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success", Integer.class)).isEqualTo(67);
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='orders'::regclass "
                + "and conname in ('chk_orders_shipping_preparation_metadata','chk_orders_shipment_metadata') "
                + "and convalidated", Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(longs = {7, 9})
    void retryKeepsFirstActorAndSkipsPaymentAndDeadlineValidation(long retryActor) {
        Fixture f = fixture(true);
        var original = preparation.start(f.orderId());
        // Make a new start ineligible. An accepted preparation must still be reusable.
        jdbc.update("update payments set status='FAILED' where id=?", f.paymentId());
        jdbc.update("update orders set change_deadline_at=? where id=?", dbNow().plusDays(1), f.orderId());
        var before = state(f);
        clearInvocations(orders, paymentService, evaluator);
        authenticate(retryActor, "retry-" + retryActor, "ROLE_ADMIN");
        var retry = preparation.start(f.orderId());
        assertThat(retry.status()).isEqualTo(ShippingPreparationStartResult.Status.ALREADY_STARTED);
        assertThat(retry.startedAt()).isEqualTo(original.startedAt());
        assertThat(retry.startedByAccountId()).isEqualTo(original.startedByAccountId());
        assertThat(retry.startedByUsername()).isEqualTo(original.startedByUsername());
        assertThat(state(f)).isEqualTo(before);
        verify(paymentService, never()).validateCardPaymentForShippingPreparation(any());
        verify(orders, never()).findCurrentTimeInTokyo();
        verifyNoInteractions(evaluator);
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void serviceRejectsPartialManagedMetadataWithoutFlushingIt(int mask) {
        Fixture f = fixture(true);
        var before = state(f);
        // With the new clean-context contract, deliberately corrupting managed state
        // must fail at entry, before any query can flush it or refresh can erase it.
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Order order = orders.findByIdForUpdate(f.orderId()).orElseThrow();
            if ((mask & 1) != 0) ReflectionTestUtils.setField(order, "shippingPreparationStartedAt", f.deadline());
            if ((mask & 2) != 0) ReflectionTestUtils.setField(order, "shippingPreparationStartedByAccountId", 7L);
            if ((mask & 4) != 0) ReflectionTestUtils.setField(order, "shippingPreparationStartedByUsername", "first");
            clearInvocations(orders);
            assertThatThrownBy(() -> preparation.start(f.orderId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("未flush");
            verify(orders, never()).findByIdForUpdate(any());
            assertThat(status.isRollbackOnly()).isTrue();
            assertThat(order.getShippingPreparationStartedAt()).isEqualTo((mask & 1) != 0 ? f.deadline() : null);
            assertThat(order.getShippingPreparationStartedByAccountId()).isEqualTo((mask & 2) != 0 ? 7L : null);
            assertThat(order.getShippingPreparationStartedByUsername()).isEqualTo((mask & 4) != 0 ? "first" : null);
            status.setRollbackOnly();
        });
        assertThat(state(f)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void v53RejectsEveryPartialPersistedCombination(int mask) {
        Fixture f = fixture(true);
        var before = state(f);
        Throwable failure = catchThrowable(() -> jdbc.update("update orders set shipping_preparation_started_at=?, "
                + "shipping_preparation_started_by_account_id=?, shipping_preparation_started_by_username=? where id=?",
                (mask & 1) != 0 ? f.deadline() : null, (mask & 2) != 0 ? 7L : null,
                (mask & 4) != 0 ? "first" : null, f.orderId()));
        Throwable cause = failure;
        while (cause != null && !(cause instanceof PSQLException)) cause = cause.getCause();
        assertThat(cause).isInstanceOf(PSQLException.class);
        PSQLException postgres = (PSQLException) cause;
        assertThat(postgres.getSQLState()).isEqualTo("23514");
        assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo("chk_orders_shipping_preparation_metadata");
        assertThat(state(f)).isEqualTo(before);
    }

    @ParameterizedTest @CsvSource({"SHIPPED,false", "SHIPPED,true", "CANCELLED,false", "CANCELLED,true"})
    void terminalStateRejectsEvenWhenPrepared(OrderStatus status, boolean prepared) {
        Fixture f = fixture(true);
        if (prepared) preparation.start(f.orderId());
        jdbc.update("update orders set status=? where id=?", status.name(), f.orderId());
        var before = state(f);
        assertThatThrownBy(() -> preparation.start(f.orderId())).isInstanceOf(IllegalStateException.class);
        assertThat(state(f)).isEqualTo(before);
    }

    @Test
    void twoAdminsSerializeAndSecondReadsFirstCommittedMetadata() throws Exception {
        Fixture f = fixture(true);
        var before = state(f);
        CompletableFuture<Integer> blocker = new CompletableFuture<>();
        Future<ShippingPreparationStartResult> first = workers.submit(() -> asAdmin(7L, "first", () -> tx(() -> {
            var result = preparation.start(f.orderId());
            blocker.complete(backendPid());
            await(release);
            return result;
        })));
        int pid = blocker.get(20, TimeUnit.SECONDS);
        Future<ShippingPreparationStartResult> second = workers.submit(() -> asAdmin(9L, "second", () -> preparation.start(f.orderId())));
        assertBlockedBy(pid);
        // The first call has returned inside its outer transaction, but has not committed.
        assertThat(orderRow(f).get("shipping_preparation_started_at")).isNull();
        release.countDown();
        var firstResult = first.get(20, TimeUnit.SECONDS);
        var secondResult = second.get(20, TimeUnit.SECONDS);
        assertThat(firstResult.status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
        assertThat(secondResult.status()).isEqualTo(ShippingPreparationStartResult.Status.ALREADY_STARTED);
        assertThat(secondResult.startedAt()).isEqualTo(firstResult.startedAt());
        assertThat(secondResult.startedByAccountId()).isEqualTo(7L);
        assertThat(secondResult.startedByUsername()).isEqualTo("first");
        assertStored(f, firstResult);
        assertThat(withoutPreparation(state(f))).isEqualTo(withoutPreparation(before));
    }

    @Test
    void orderWaitRechecksCommittedCancellation() throws Exception {
        Fixture f = fixture(true);
        CompletableFuture<Integer> blocker = new CompletableFuture<>();
        Future<?> first = workers.submit(() -> asAdmin(7L, "holder", () -> tx(() -> {
            orders.findByIdForUpdate(f.orderId()).orElseThrow();
            jdbc.update("update orders set status='CANCELLED' where id=?", f.orderId());
            blocker.complete(backendPid()); await(release); return null;
        })));
        int pid = blocker.get(20, TimeUnit.SECONDS);
        clearInvocations(orders, paymentService);
        Future<?> second = workers.submit(() -> asAdmin(9L, "second", () -> preparation.start(f.orderId())));
        assertBlockedBy(pid);
        verify(orders, never()).findCurrentTimeInTokyo();
        release.countDown(); first.get(20, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(orderRow(f)).containsEntry("status", "CANCELLED");
        assertUnprepared(f);
        verify(paymentService, never()).validateCardPaymentForShippingPreparation(any());
    }

    @Test
    void databaseTimeIsReadOnlyAfterWaitingForPaymentLock() throws Exception {
        Fixture f = fixture(true);
        CompletableFuture<Integer> blocker = new CompletableFuture<>();
        Future<?> first = workers.submit(() -> asAdmin(7L, "holder", () -> tx(() -> {
            payments.findByOrderIdForUpdate(f.orderId());
            blocker.complete(backendPid()); await(release); return null;
        })));
        int pid = blocker.get(20, TimeUnit.SECONDS);
        clearInvocations(orders);
        Future<ShippingPreparationStartResult> second = workers.submit(() -> asAdmin(9L, "second", () -> preparation.start(f.orderId())));
        assertBlockedBy(pid);
        verify(orders, never()).findCurrentTimeInTokyo();
        LocalDateTime justBeforeRelease = dbNow();
        release.countDown(); first.get(20, TimeUnit.SECONDS);
        var result = second.get(20, TimeUnit.SECONDS);
        assertThat(result.startedAt()).isAfterOrEqualTo(justBeforeRelease);
        assertStored(f, result);
    }

    @Test
    void acquiredPaymentLockRemainsHeldUntilOuterCommit() throws Exception {
        Fixture f = fixture(true);
        CompletableFuture<Integer> blocker = new CompletableFuture<>();
        Future<ShippingPreparationStartResult> first = workers.submit(() -> asAdmin(7L, "first", () -> tx(() -> {
            var result = preparation.start(f.orderId());
            blocker.complete(backendPid()); await(release); return result;
        })));
        int pid = blocker.get(20, TimeUnit.SECONDS);
        Future<Integer> second = workers.submit(() -> asAdmin(9L, "second", () -> tx(() -> {
            payments.findByOrderIdForUpdate(f.orderId());
            return backendPid();
        })));
        assertBlockedBy(pid);
        verifyNoInteractions(gateway);
        release.countDown();
        var result = first.get(20, TimeUnit.SECONDS);
        assertThat(second.get(20, TimeUnit.SECONDS)).isNotEqualTo(pid);
        assertStored(f, result);
    }

    @Test
    void paymentWaitRechecksLostAuthorization() throws Exception {
        paymentChangeWhileWaiting(null);
    }

    @ParameterizedTest @EnumSource(PaymentTransactionType.class)
    void paymentWaitRechecksNewPendingTransaction(PaymentTransactionType type) throws Exception {
        paymentChangeWhileWaiting(type);
    }

    private void paymentChangeWhileWaiting(PaymentTransactionType type) throws Exception {
        Fixture f = fixture(true);
        CompletableFuture<Integer> blocker = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> expected = new CompletableFuture<>();
        Future<?> first = workers.submit(() -> asAdmin(7L, "holder", () -> tx(() -> {
            // Test-only holder never requests Order, avoiding an artificial lock cycle.
            payments.findByOrderIdForUpdate(f.orderId());
            blocker.complete(backendPid()); await(release);
            if (type == null) jdbc.update("update payments set status='CANCELLED' where id=?", f.paymentId());
            else {
                Payment payment = payments.findById(f.paymentId()).orElseThrow();
                transactions.saveAndFlush(new PaymentTransaction(payment, type, 3000, 0,
                        UUID.randomUUID().toString(), dbNow()));
            }
            expected.complete(state(f));
            return null;
        })));
        int pid = blocker.get(20, TimeUnit.SECONDS);
        clearInvocations(orders);
        Future<?> second = workers.submit(() -> asAdmin(9L, "second", () -> preparation.start(f.orderId())));
        assertBlockedBy(pid);
        verify(orders, never()).findCurrentTimeInTokyo();
        release.countDown(); first.get(20, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(state(f)).isEqualTo(expected.get(20, TimeUnit.SECONDS));
        assertUnprepared(f);
        verify(orders, never()).findCurrentTimeInTokyo();
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void microsecondDeadlineBoundaryUsesRealPolicy(int offsetMicros) {
        Fixture f = fixture(true);
        // Control only the DB-time repository result; all persistence/locks are real PostgreSQL.
        doReturn(f.deadline().plusNanos(offsetMicros * 1000L)).when(orders).findCurrentTimeInTokyo();
        var before = state(f);
        if (offsetMicros < 0) {
            assertThatThrownBy(() -> preparation.start(f.orderId())).isInstanceOf(IllegalStateException.class);
            assertThat(state(f)).isEqualTo(before);
        } else {
            var result = preparation.start(f.orderId());
            assertThat(result.startedAt()).isEqualTo(f.deadline().plusNanos(offsetMicros * 1000L));
            assertStored(f, result);
        }
    }

    @Test
    void crossingDeadlineDuringOrderWaitUsesTimeAfterLock() throws Exception {
        Fixture f = fixture(true);
        var clock = new AtomicReference<>(f.deadline().minusNanos(1000));
        doAnswer(invocation -> clock.get()).when(orders).findCurrentTimeInTokyo();
        CompletableFuture<Integer> blocker = new CompletableFuture<>();
        Future<?> first = workers.submit(() -> asAdmin(7L, "holder", () -> tx(() -> {
            orders.findByIdForUpdate(f.orderId()).orElseThrow();
            blocker.complete(backendPid()); await(release); return null;
        })));
        int pid = blocker.get(20, TimeUnit.SECONDS);
        clearInvocations(orders);
        Future<ShippingPreparationStartResult> second = workers.submit(() -> asAdmin(9L, "second", () -> preparation.start(f.orderId())));
        assertBlockedBy(pid);
        verify(orders, never()).findCurrentTimeInTokyo();
        clock.set(f.deadline()); release.countDown(); first.get(20, TimeUnit.SECONDS);
        var result = second.get(20, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
        assertThat(result.startedAt()).isEqualTo(f.deadline());
        assertStored(f, result);
    }

    @Test
    void precedingQuantityDecreaseIsSeenAfterOrderLockWait() throws Exception {
        Fixture f = fixture(true);
        var clock = new AtomicReference<>(f.deadline().minusNanos(1000));
        doAnswer(invocation -> clock.get()).when(orders).findCurrentTimeInTokyo();
        when(calculator.calculate(any(), any(ChargeTaxSnapshot.class))).thenReturn(amount(f.taxId(), 1000));
        CompletableFuture<Integer> blocker = new CompletableFuture<>();
        Future<?> first = workers.submit(() -> asAdmin(7L, "first", () -> tx(() -> {
            assertThat(orderService.changeItemsForUser(f.orderId(), f.userId(), "customer", itemChange(f))).isTrue();
            blocker.complete(backendPid()); await(release); return null;
        })));
        int pid = blocker.get(20, TimeUnit.SECONDS);
        clearInvocations(evaluator);
        Future<ShippingPreparationStartResult> second = workers.submit(() -> asAdmin(9L, "second", () -> preparation.start(f.orderId())));
        assertBlockedBy(pid); clock.set(f.deadline()); release.countDown(); first.get(20, TimeUnit.SECONDS);
        var result = second.get(20, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
        assertThat(orderRow(f)).containsEntry("total_amount", 1000).containsEntry("content_revision", 1);
        verify(evaluator).evaluate(argThat(o -> o.getTotalAmount() == 1000 && o.getContentRevision() == 1),
                argThat(p -> p.getAmount() == 3000), argThat(t -> t.getOrderContentRevision() == 0));
        assertThat(count("order_content_change_histories", f)).isEqualTo(1);
        assertStored(f, result);
    }

    enum Modification { ADDRESS, ADDRESS_NO_OP, ITEMS, LOCAL_CUSTOMER_CANCEL, LOCAL_ADMIN_CANCEL, CARD_CUSTOMER_CANCEL, CARD_ADMIN_CANCEL }
    @ParameterizedTest @EnumSource(Modification.class)
    void preparationBlocksExistingModificationAndCancellationRoutes(Modification operation) {
        Fixture f = fixture(true);
        preparation.start(f.orderId());
        // Keep deadline open to isolate preparation guards from deadline guards.
        jdbc.update("update orders set change_deadline_at=? where id=?", dbNow().plusDays(1), f.orderId());
        if (operation == Modification.LOCAL_CUSTOMER_CANCEL || operation == Modification.LOCAL_ADMIN_CANCEL)
            jdbc.update("update payments set status='CANCELLED' where id=?", f.paymentId());
        var before = state(f);
        assertThatThrownBy(() -> {
            switch (operation) {
                case ADDRESS, ADDRESS_NO_OP -> orderService.changeShippingAddressForUser(f.orderId(), f.userId(), "customer",
                        address(operation == Modification.ADDRESS ? "Changed" : "Original"));
                case ITEMS -> orderService.changeItemsForUser(f.orderId(), f.userId(), "customer", itemChange(f));
                case LOCAL_CUSTOMER_CANCEL -> orderService.cancelOrderForUser(f.orderId(), f.userId(), "customer");
                case LOCAL_ADMIN_CANCEL -> orderService.cancelOrder(f.orderId(), 7L, "first", null);
                case CARD_CUSTOMER_CANCEL -> cancellation.start(f.orderId(), f.userId(), "customer");
                case CARD_ADMIN_CANCEL -> adminCancellation.cancel(f.orderId(), 7L, "first", null);
            }
        }).isInstanceOf(InvalidOrderStatusException.class).hasMessageContaining("変更受付");
        assertThat(state(f)).isEqualTo(before);
        verifyNoInteractions(calculator);
    }

    @Test
    void invalidCardRollsBackWithoutSideEffects() {
        Fixture f = fixture(true);
        jdbc.update("update payments set provider_payment_id=null where id=?", f.paymentId());
        assertRejectedUnchanged(f, IllegalStateException.class);
    }

    @Test
    void invalidManualPaymentRollsBackWithoutSideEffects() {
        Fixture f = fixture(true);
        jdbc.update("update orders set status='PAID' where id=?", f.orderId());
        assertRejectedUnchanged(f, InvalidOrderStatusException.class);
    }

    @Test
    void databaseClockFailureDoesNotPersistAnything() {
        Fixture f = fixture(true);
        doThrow(new IllegalStateException("test clock read failure")).when(orders).findCurrentTimeInTokyo();
        assertRejectedUnchanged(f, IllegalStateException.class);
    }

    @Test
    void rollbackAfterFlushedPreparationRemovesAllThreeFields() {
        Fixture f = fixture(true);
        var before = state(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var result = preparation.start(f.orderId());
            orders.flush();
            assertStored(f, result); // JDBC shares this transaction: verifies an UPDATE really reached PostgreSQL.
            status.setRollbackOnly();
        });
        assertThat(state(f)).isEqualTo(before);
        assertUnprepared(f);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unauthenticatedOrNonAdminCannotMutate(boolean absent) {
        Fixture f = fixture(true);
        if (absent) SecurityContextHolder.clearContext();
        else authenticate(7L, "customer", "ROLE_USER");
        clearInvocations(orders);
        assertRejectedUnchanged(f, AccessDeniedException.class);
        verify(orders, never()).findByIdForUpdate(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preloadedOrderPreservesAnotherTransactionsPreparation(boolean alsoPayment) throws Exception {
        Fixture f = fixture(true);
        var call = preloadThenCommit(f, true, alsoPayment, false, () -> preparation.start(f.orderId()));
        var result = call.result().get(20, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(ShippingPreparationStartResult.Status.ALREADY_STARTED);
        assertThat(result.startedByAccountId()).isEqualTo(7L);
        assertThat(result.startedByUsername()).isEqualTo("first");
        assertThat(state(f)).isEqualTo(call.committedState());
        assertStored(f, result);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preloadedOrderCannotIgnoreCommittedCancellation(boolean alsoPayment) throws Exception {
        Fixture f = fixture(true);
        var call = preloadThenCommit(f, true, alsoPayment, false,
                () -> jdbc.update("update orders set status='CANCELLED' where id=?", f.orderId()));
        assertThatThrownBy(() -> call.result().get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(state(f)).isEqualTo(call.committedState());
        assertUnprepared(f);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preloadedPaymentCannotIgnoreLostAuthorization(boolean alsoOrder) throws Exception {
        Fixture f = fixture(true);
        var call = preloadThenCommit(f, alsoOrder, true, false,
                () -> jdbc.update("update payments set status='CANCELLED' where id=?", f.paymentId()));
        assertThatThrownBy(() -> call.result().get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(state(f)).isEqualTo(call.committedState());
        assertUnprepared(f);
    }

    @ParameterizedTest @EnumSource(PaymentTransactionType.class)
    void preloadedPaymentStillSeesNewPendingTransactions(PaymentTransactionType type) throws Exception {
        Fixture f = fixture(true);
        var call = preloadThenCommit(f, true, true, true, () -> transactions.saveAndFlush(new PaymentTransaction(
                payments.findById(f.paymentId()).orElseThrow(), type, 3000, 0, UUID.randomUUID().toString(), dbNow())));
        assertThatThrownBy(() -> call.result().get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(state(f)).isEqualTo(call.committedState());
        assertUnprepared(f);
    }

    @Test
    void preloadedPendingTransactionIsRefreshedAfterCompletion() throws Exception {
        Fixture f = fixture(true);
        Long pendingId = tx(() -> transactions.saveAndFlush(new PaymentTransaction(
                payments.findById(f.paymentId()).orElseThrow(), PaymentTransactionType.CAPTURE, 3000, 0,
                UUID.randomUUID().toString(), dbNow())).getId());
        var call = preloadThenCommit(f, true, true, true,
                () -> transactions.findById(pendingId).orElseThrow().markFailed(null, "test", "completed", dbNow()));
        var result = call.result().get(20, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
        assertThat(withoutPreparation(state(f))).isEqualTo(withoutPreparation(call.committedState()));
        assertStored(f, result);
    }

    enum DirtyEntity { ORDER, PAYMENT, TRANSACTION, UNRELATED_PRODUCT, ITEM, CHARGE }
    @ParameterizedTest @EnumSource(DirtyEntity.class)
    void dirtyContextIsRejectedWithoutFlushRefreshOrDiscard(DirtyEntity target) {
        Fixture f = fixture(true);
        Fixture unrelated = target == DirtyEntity.UNRELATED_PRODUCT ? fixture(false) : f;
        var before = state(f);
        var unrelatedBefore = state(unrelated);
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                Order order = orders.findById(f.orderId()).orElseThrow();
                Payment payment = payments.findById(f.paymentId()).orElseThrow();
                PaymentTransaction transaction = transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(f.paymentId()).getFirst();
                Product product = products.findById(unrelated.productIds().getFirst()).orElseThrow();
                switch (target) {
                    case ORDER -> order.incrementContentRevision();
                    case PAYMENT -> payment.setProviderPaymentId("dirty-provider", dbNow());
                    case TRANSACTION -> ReflectionTestUtils.setField(transaction, "providerTransactionId", "dirty-transaction");
                    case UNRELATED_PRODUCT -> product.setName("dirty-product");
                    case ITEM -> ReflectionTestUtils.setField(order.getItems().getFirst(), "quantity", 1);
                    case CHARGE -> ReflectionTestUtils.setField(order.getCharges().getFirst(), "name", "dirty-charge");
                }
                long flushes = statistics.getFlushCount();
                long preparedStatements = statistics.getPrepareStatementCount();
                clearInvocations(orders, paymentService);
                assertThatThrownBy(() -> preparation.start(f.orderId()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("未flush");
                assertThat(statistics.getFlushCount()).isEqualTo(flushes);
                assertThat(statistics.getPrepareStatementCount()).isEqualTo(preparedStatements);
                verify(orders, never()).findByIdForUpdate(any());
                verify(paymentService, never()).validateCardPaymentForShippingPreparation(any());
                assertThat(status.isRollbackOnly()).isTrue();
                switch (target) {
                    case ORDER -> assertThat(order.getContentRevision()).isEqualTo(1);
                    case PAYMENT -> assertThat(payment.getProviderPaymentId()).isEqualTo("dirty-provider");
                    case TRANSACTION -> assertThat(transaction.getProviderTransactionId()).isEqualTo("dirty-transaction");
                    case UNRELATED_PRODUCT -> assertThat(product.getName()).isEqualTo("dirty-product");
                    case ITEM -> assertThat(order.getItems().getFirst().getQuantity()).isEqualTo(1);
                    case CHARGE -> assertThat(order.getCharges().getFirst().getName()).isEqualTo("dirty-charge");
                }
                // Inspection above is in memory only: the caller's edits were not refreshed away.
                status.setRollbackOnly();
            });
        } finally { statistics.setStatisticsEnabled(enabled); }
        assertThat(state(f)).isEqualTo(before);
        assertThat(state(unrelated)).isEqualTo(unrelatedBefore);
        assertUnprepared(f);
    }

    @Test
    void alreadyFlushedUncommittedChangesAreNotDetectedButRemainInOuterRollback() {
        Fixture f = fixture(true);
        var before = state(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Order order = orders.findByIdForUpdate(f.orderId()).orElseThrow();
            payments.findByOrderIdForUpdate(f.orderId());
            Product product = products.findById(f.productIds().getFirst()).orElseThrow();
            product.setName("flushed-but-uncommitted");
            entityManager.flush();
            // Contract limitation: isDirty cannot detect writes already flushed in this transaction.
            assertThat(entityManager.unwrap(Session.class).isDirty()).isFalse();
            var result = preparation.start(f.orderId());
            assertThat(result.status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
            assertThat(product.getName()).isEqualTo("flushed-but-uncommitted");
            entityManager.flush();
            assertStored(f, result);
            status.setRollbackOnly();
        });
        assertThat(state(f)).isEqualTo(before);
    }

    @Test
    void readOnlyOuterTransactionIsExplicitlyRejected() {
        Fixture f = fixture(true);
        var before = state(f);
        TransactionTemplate transaction = new TransactionTemplate(manager); transaction.setReadOnly(true);
        transaction.executeWithoutResult(status -> {
            clearInvocations(orders);
            assertThatThrownBy(() -> preparation.start(f.orderId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("書込み");
            verify(orders, never()).findByIdForUpdate(any());
            assertThat(status.isRollbackOnly()).isTrue(); status.setRollbackOnly();
        });
        assertThat(state(f)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(ints = {Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE})
    void unsupportedActualIsolationIsRejected(int isolation) {
        Fixture f = fixture(true);
        var before = state(f);
        TransactionTemplate transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
        transaction.executeWithoutResult(status -> {
            assertThat(entityManager.unwrap(Session.class).doReturningWork(Connection::getTransactionIsolation)).isEqualTo(isolation);
            clearInvocations(orders);
            assertThatThrownBy(() -> preparation.start(f.orderId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ COMMITTED");
            verify(orders, never()).findByIdForUpdate(any());
            assertThat(status.isRollbackOnly()).isTrue(); status.setRollbackOnly();
        });
        assertThat(state(f)).isEqualTo(before);
    }

    @Test
    void actualConnectionIsolationIsCheckedEvenWhenSpringMetadataIsUnspecified() {
        Fixture f = fixture(true);
        var before = state(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();
            jdbc.execute("set transaction isolation level repeatable read");
            assertThat(entityManager.unwrap(Session.class).doReturningWork(Connection::getTransactionIsolation))
                    .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            clearInvocations(orders);
            assertThatThrownBy(() -> preparation.start(f.orderId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ COMMITTED");
            verify(orders, never()).findByIdForUpdate(any());
            status.setRollbackOnly();
        });
        assertThat(state(f)).isEqualTo(before);
    }

    @Test
    void serverReadOnlyIsRejectedEvenWhenSpringMetadataIsWritable() {
        Fixture f = fixture(true);
        var before = state(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            jdbc.execute("set transaction read only");
            clearInvocations(orders);
            assertThatThrownBy(() -> preparation.start(f.orderId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("書込み");
            verify(orders, never()).findByIdForUpdate(any());
            assertThat(status.isRollbackOnly()).isTrue(); status.setRollbackOnly();
        });
        assertThat(state(f)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void individuallyReadOnlyOrderRejectsBeforeRefreshAndRollsBack(boolean prepared) {
        Fixture f = fixture(true);
        if (prepared) preparation.start(f.orderId());
        var before = state(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Order order = orders.findById(f.orderId()).orElseThrow();
            Session session = entityManager.unwrap(Session.class);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(session.isDefaultReadOnly()).isFalse();
            assertThat(session.getHibernateFlushMode()).isEqualTo(org.hibernate.FlushMode.AUTO);
            assertThat(jdbc.queryForObject("select current_setting('transaction_read_only')", String.class)).isEqualTo("off");
            session.setReadOnly(order, true);
            assertThat(session.isReadOnly(order)).isTrue();
            assertThat(session.isDirty()).isFalse();
            var beforeAt = order.getShippingPreparationStartedAt();
            var beforeId = order.getShippingPreparationStartedByAccountId();
            var beforeName = order.getShippingPreparationStartedByUsername();
            AtomicInteger refreshes = new AtomicInteger();
            var factory = entityManager.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class);
            var registry = factory.getServiceRegistry().getService(EventListenerRegistry.class);
            List<RefreshEventListener> original = new ArrayList<>();
            registry.getEventListenerGroup(EventType.REFRESH).listeners().forEach(original::add);
            // Observe REAL refresh events; do not mock/delegate the EntityManager or suppress defaults.
            registry.prependListeners(EventType.REFRESH, new RefreshEventListener() {
                private void observe(RefreshEvent event) {
                    if (event.getObject() instanceof Order refreshed && Objects.equals(refreshed.getId(), f.orderId()))
                        refreshes.incrementAndGet();
                }
                @Override public void onRefresh(RefreshEvent event) { observe(event); }
                @Override public void onRefresh(RefreshEvent event, RefreshContext context) { observe(event); }
            });
            clearInvocations(orders, paymentService);
            try {
                assertThatThrownBy(() -> preparation.start(f.orderId()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("読取り専用");
                verify(orders).findByIdForUpdate(f.orderId());
                verify(orders, never()).findCurrentTimeInTokyo();
                verify(paymentService, never()).validateCardPaymentForShippingPreparation(any());
                assertThat(refreshes).hasValue(0);
                assertThat(session.isReadOnly(order)).isTrue();
                assertThat(order.getShippingPreparationStartedAt()).isEqualTo(beforeAt);
                assertThat(order.getShippingPreparationStartedByAccountId()).isEqualTo(beforeId);
                assertThat(order.getShippingPreparationStartedByUsername()).isEqualTo(beforeName);
                assertThat(status.isRollbackOnly()).isTrue();
                status.setRollbackOnly();
            } finally {
                registry.setListeners(EventType.REFRESH, original.toArray(RefreshEventListener[]::new));
            }
        });
        assertThat(state(f)).isEqualTo(before);
        if (!prepared) assertUnprepared(f);
    }

    @Test
    void unrelatedReadOnlyEntityDoesNotPreventWritableOrderPreparation() {
        Fixture f = fixture(true);
        Fixture unrelated = fixture(false);
        var before = state(f);
        var unrelatedBefore = state(unrelated);
        var result = tx(() -> {
            Session session = entityManager.unwrap(Session.class);
            Product product = products.findById(unrelated.productIds().getFirst()).orElseThrow();
            Order order = orders.findById(f.orderId()).orElseThrow();
            session.setReadOnly(product, true);
            assertThat(session.isDefaultReadOnly()).isFalse();
            assertThat(session.isReadOnly(product)).isTrue();
            assertThat(session.isReadOnly(order)).isFalse();
            var started = preparation.start(f.orderId());
            assertThat(session.isReadOnly(product)).isTrue();
            assertThat(session.isReadOnly(order)).isFalse();
            entityManager.flush(); assertStored(f, started);
            return started;
        });
        assertThat(result.status()).isEqualTo(ShippingPreparationStartResult.Status.STARTED);
        assertStored(f, result);
        assertThat(withoutPreparation(state(f))).isEqualTo(withoutPreparation(before));
        assertThat(state(unrelated)).isEqualTo(unrelatedBefore);
    }

    @Test
    void orderRefreshCascadesLatestItemsAndChargesWithoutOtherChanges() throws Exception {
        Fixture f = fixture(true);
        var call = preloadThenCommit(f, true, true, true, () -> {
            jdbc.update("update order_items set product_name='committed-item' where id=?", f.itemIds().getFirst());
            jdbc.update("update order_charges set name='committed-charge' where order_id=?", f.orderId());
        });
        var result = call.result().get(20, TimeUnit.SECONDS);
        assertThat(withoutPreparation(state(f))).isEqualTo(withoutPreparation(call.committedState()));
        assertStored(f, result);
    }

    private PreloadedCall preloadThenCommit(Fixture f, boolean loadOrder, boolean loadPayment, boolean loadHistory,
            Runnable change) throws Exception {
        CompletableFuture<Integer> ready = new CompletableFuture<>();
        CountDownLatch committed = new CountDownLatch(1);
        Future<ShippingPreparationStartResult> result = workers.submit(() -> asAdmin(9L, "second", () -> tx(() -> {
            Order order = null;
            if (loadOrder) {
                order = orders.findById(f.orderId()).orElseThrow();
                order.getItems().size(); order.getCharges().size();
            }
            if (loadPayment) payments.findById(f.paymentId()).orElseThrow();
            if (loadHistory) transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(f.paymentId()).size();
            ready.complete(backendPid()); await(committed);
            var started = preparation.start(f.orderId());
            if (loadOrder && started.status() == ShippingPreparationStartResult.Status.STARTED) {
                // Refresh keeps object identity and updates the already-initialized cascade collections.
                assertThat(entityManager.find(Order.class, f.orderId())).isSameAs(order);
                // @OneToMany has no OrderBy/OrderColumn: identify the row by ID after refresh.
                OrderItem item = order.getItems().stream()
                        .filter(i -> Objects.equals(i.getId(), f.itemIds().getFirst())).findFirst().orElseThrow();
                assertThat(item.getProductName()).as("refreshed item id %s", item.getId()).isEqualTo(
                        jdbc.queryForObject("select product_name from order_items where id=?", String.class, item.getId()));
                assertThat(order.getCharges().getFirst().getName()).isEqualTo(
                        jdbc.queryForObject("select name from order_charges where order_id=?", String.class, f.orderId()));
            }
            return started;
        })));
        int firstPid = ready.get(20, TimeUnit.SECONDS);
        Map<String, Object> after;
        try {
            after = tx(() -> {
                assertThat(backendPid()).isNotEqualTo(firstPid);
                orders.findByIdForUpdate(f.orderId()).orElseThrow();
                payments.findByOrderIdForUpdate(f.orderId());
                change.run(); entityManager.flush(); return state(f);
            });
        } finally { committed.countDown(); }
        return new PreloadedCall(result, after);
    }

    private record PreloadedCall(Future<ShippingPreparationStartResult> result, Map<String, Object> committedState) {}

    private void assertRejectedUnchanged(Fixture f, Class<? extends Throwable> error) {
        var before = state(f);
        assertThatThrownBy(() -> preparation.start(f.orderId())).isInstanceOf(error);
        assertThat(state(f)).isEqualTo(before);
        assertUnprepared(f);
    }

    private void assertStored(Fixture f, ShippingPreparationStartResult result) {
        var row = orderRow(f);
        assertThat(row.get("shipping_preparation_started_at")).isEqualTo(java.sql.Timestamp.valueOf(result.startedAt()));
        assertThat(row.get("shipping_preparation_started_by_account_id")).isEqualTo(result.startedByAccountId());
        assertThat(row.get("shipping_preparation_started_by_username")).isEqualTo(result.startedByUsername());
        assertThat(row.get("shipment_capture_payment_id")).isNull();
    }

    private void assertUnprepared(Fixture f) {
        var row = orderRow(f);
        for (String name : preparationColumns()) assertThat(row.get(name)).as(name).isNull();
    }

    private List<String> preparationColumns() {
        return List.of("shipping_preparation_started_at", "shipping_preparation_started_by_account_id", "shipping_preparation_started_by_username");
    }

    private Map<String, Object> orderRow(Fixture f) { return jdbc.queryForMap("select * from orders where id=?", f.orderId()); }

    private Map<String, Object> withoutPreparation(Map<String, Object> state) {
        @SuppressWarnings("unchecked") var row = new LinkedHashMap<>((Map<String, Object>) state.get("order"));
        preparationColumns().forEach(row::remove);
        var copy = new LinkedHashMap<>(state); copy.put("order", row); return copy;
    }

    private Map<String, Object> state(Fixture f) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("order", orderRow(f));
        for (String table : List.of("order_items", "order_charges", "payments", "stock_movements",
                "order_status_histories", "order_content_change_histories", "order_shipping_address_histories")) {
            state.put(table, jdbc.queryForList("select * from " + table + " where order_id=? order by id", f.orderId()));
        }
        state.put("transactions", jdbc.queryForList("select t.* from payment_transactions t join payments p "
                + "on p.id=t.payment_id where p.order_id=? order by t.id", f.orderId()));
        state.put("stock", jdbc.queryForList("select * from products where id in (?,?) order by id", f.productIds().get(0), f.productIds().get(1)));
        return state;
    }

    private int count(String table, Fixture f) {
        return jdbc.queryForObject("select count(*) from " + table + " where order_id=?", Integer.class, f.orderId());
    }

    private Fixture fixture(boolean card) {
        return tx(() -> {
            String unique = UUID.randomUUID().toString(); LocalDateTime now = dbNow();
            User user = new User(); user.setUsername("preparation-" + unique); user.setPassword("password");
            user.setEnabled(true); user.setCreatedAt(now); user.setUpdatedAt(now); users.saveAndFlush(user);
            Category category = categories.saveAndFlush(new Category("preparation-" + unique));
            TaxCategory tax = taxes.findAll().getFirst();
            LocalDateTime orderedAt = now.minusDays(1).toLocalDate().atTime(10, 0);
            Order order = new Order(user.getId(), 3000, orderedAt, orderedAt.withHour(14));
            List<Long> productIds = new ArrayList<>();
            for (int price : List.of(1000, 500)) {
                Product product = new Product(); product.setName("preparation fixture"); product.setPrice(price);
                product.setStock(10); product.setCategory(category); product.setTaxCategory(tax);
                products.saveAndFlush(product); productIds.add(product.getId());
                order.addItem(new OrderItem(product.getId(), product.getName(), category.getId(), category.getName(),
                        tax.getId(), tax.getCode(), tax.getName(), tax.getTaxRate(), price, 2));
            }
            order.addCharge(new OrderCharge(OrderChargeType.SHIPPING, "shipping", 0, tax.getId(), tax.getCode(), tax.getName(), tax.getTaxRate(), 1));
            order.applyAmount(amount(tax.getId(), 3000));
            order.setShippingAddress("Original", "100-0001", "Tokyo", "Chiyoda", "1-1", "09012345678");
            if (!card) order.markAsPaid();
            orders.saveAndFlush(order);
            Long paymentId = null;
            if (card) {
                Payment payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 3000, now);
                String providerId = "pf_" + unique; payment.setProviderPaymentId(providerId, now); payment.markAuthorized(now);
                payments.saveAndFlush(payment); paymentId = payment.getId();
                PaymentTransaction authorization = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE, 3000, 0, unique, now);
                authorization.markSucceeded(providerId, now); transactions.saveAndFlush(authorization);
            }
            return new Fixture(order.getId(), user.getId(), paymentId, productIds,
                    order.getItems().stream().map(OrderItem::getId).toList(), tax.getId(), order.getChangeDeadlineAt());
        });
    }

    private OrderAmount amount(Long taxId, int total) {
        return new OrderAmount(total, List.of(new OrderChargeAmount(OrderChargeType.SHIPPING, "shipping", 0,
                taxId, "test", "test", BigDecimal.ZERO, 1)), 0, 0, total);
    }

    private OrderItemChangeForm itemChange(Fixture f) {
        OrderItemChangeForm form = new OrderItemChangeForm(); form.setContentRevision(0);
        for (int i = 0; i < 2; i++) {
            var item = new OrderItemChangeForm.Item(); item.setOrderItemId(f.itemIds().get(i));
            item.setQuantity(i == 0 ? 1 : 0); form.getItems().add(item);
        }
        return form;
    }

    private OrderShippingAddressForm address(String name) {
        OrderShippingAddressForm form = new OrderShippingAddressForm(); form.setShippingName(name);
        form.setShippingPostalCode("100-0001"); form.setShippingPrefecture("Tokyo"); form.setShippingCity("Chiyoda");
        form.setShippingAddressLine("1-1"); form.setShippingPhone("09012345678"); return form;
    }

    private LocalDateTime dbNow() { return jdbc.queryForObject("select clock_timestamp() AT TIME ZONE 'Asia/Tokyo'", LocalDateTime.class); }
    private int backendPid() { return jdbc.queryForObject("select pg_backend_pid()", Integer.class); }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(status -> action.get()); }

    private void authenticate(Long id, String username, String role) {
        var authorities = List.of(new SimpleGrantedAuthority(role));
        var principal = new AdminUserDetails(id, username, "password", true, authorities);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "password", authorities));
    }

    private <T> T asAdmin(Long id, String username, Supplier<T> action) {
        try { authenticate(id, username, "ROLE_ADMIN"); return action.get(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).as("controlled interleaving").isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private void assertBlockedBy(int blockerPid) throws InterruptedException {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < limit) {
            var pids = jdbc.queryForList("select pid from pg_stat_activity where datname=current_database() "
                    + "and ?=any(pg_blocking_pids(pid))", Integer.class, blockerPid);
            if (!pids.isEmpty()) {
                assertThat(pids).allMatch(pid -> pid != blockerPid);
                return;
            }
            new CountDownLatch(1).await(25, TimeUnit.MILLISECONDS);
        }
        fail("Expected an independent PostgreSQL backend blocked by %s", blockerPid);
    }

    private record Fixture(Long orderId, Long userId, Long paymentId, List<Long> productIds,
            List<Long> itemIds, Long taxId, LocalDateTime deadline) {}
}
