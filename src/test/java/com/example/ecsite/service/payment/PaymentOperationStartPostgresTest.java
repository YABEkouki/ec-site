package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(showSql = false, properties = {"spring.jpa.show-sql=false", "spring.sql.init.mode=never"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.example.ecsite.config.PayJpSafetyProperties.class)
@Import({CaptureShipmentOperations.class,CaptureShipmentPolicy.class,CaptureShipmentSnapshotMatcher.class,com.example.ecsite.service.OrderWriteContext.class,com.example.ecsite.service.OrderShipmentGuard.class,com.example.ecsite.service.OrderStatusHistoryService.class,com.example.ecsite.service.OrderModificationGuard.class,com.example.ecsite.service.OrderModificationPolicy.class,PaymentPostGuardService.class, com.example.ecsite.config.TimeConfig.class,PaymentOperationSnapshotService.class, PaymentAuthorizationResultService.class,OrderPaymentGuard.class, PaymentCancellationCompletionService.class, PaymentAuthorizationStartService.class, PaymentService.class, PaymentConsistencyEvaluator.class,
        PaymentAuthorizationService.class, PaymentCaptureService.class, PaymentCaptureStartService.class,
        PaymentCancellationService.class, PaymentCancellationStartService.class,
        AdminPaymentCancellationService.class, AdminPaymentCancellationStartService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOperationStartPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature120_start").withUsername("feature120").withPassword("feature120-test")
            .withCommand("postgres", "-c", "lock_timeout=10000", "-c", "statement_timeout=15000");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
    }
    @Autowired PaymentAuthorizationService authorization;
    @Autowired PaymentCaptureService capture;
    @Autowired PaymentCancellationService cancellation;
    @Autowired AdminPaymentCancellationService adminCancellation;
    @Autowired PaymentCaptureStartService captureStart;
    @Autowired AdminPaymentCancellationStartService cancelStart;
    @Autowired PaymentService paymentService;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @MockitoBean com.example.ecsite.service.OrderService orderService;
    @MockitoBean PaymentGateway gateway;
    @Autowired PaymentAuthorizationResultService authorizationResult;
    @MockitoBean PaymentCaptureResultService captureResult;
    @MockitoBean PaymentCancellationResultService cancellationResult;
    @MockitoBean AdminPaymentCancellationResultService adminCancellationResult;
    ExecutorService workers;
    CountDownLatch entered, release;
    @BeforeEach void setup() {
        when(captureResult.apply(any(),any())).thenReturn(PaymentResultApplication.APPLIED);
        when(cancellationResult.apply(any(),any())).thenReturn(PaymentResultApplication.APPLIED);
        when(adminCancellationResult.apply(any(),any())).thenReturn(PaymentResultApplication.APPLIED);
        workers = Executors.newFixedThreadPool(2);
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
    }
    @AfterEach void cleanup() throws Exception {
        release.countDown(); workers.shutdownNow();
        assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
    @Test void simultaneousAuthorizationCreatesOneOperationAndPostsOnce() throws Exception {
        Fixture f = fixture(false);
        when(gateway.prepareAuthorization(any())).thenAnswer(call -> {
            boundary(f, "AUTHORIZE"); if (entered.getCount() == 1) { entered.countDown(); await(release); }
            return new AuthorizationPreparation("pf_" + f.order.getId(), "secret");
        });
        Future<?> first = workers.submit(() -> authorization.prepareAuthorization(f.order));
        await(entered);
        assertThatThrownBy(() -> authorization.prepareAuthorization(f.order)).isInstanceOf(IllegalStateException.class);
        assertRows(f, "AUTHORIZE", 1);
        release.countDown(); first.get(20, TimeUnit.SECONDS);
        verify(gateway, times(1)).prepareAuthorization(any());
    }
    @Test void simultaneousCaptureReusesPendingAndGetsWithoutPosting() throws Exception {
        Fixture f = fixture(true);
        when(gateway.capture(any())).thenAnswer(call -> {
            boundary(f, "CAPTURE"); if (entered.getCount() == 1) { entered.countDown(); await(release); }
            return new CaptureResult(CaptureResultStatus.PENDING, f.providerId, null, null);
        });
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(call -> {
            boundary(f, "CAPTURE"); return new PaymentFlowState(f.providerId, PaymentFlowStatus.REQUIRES_CAPTURE, null, null);
        });
        Future<?> first = workers.submit(() -> captureAsAdmin(f.order.getId(), 1L, "admin", null));
        await(entered);
        assertThat(captureAsAdmin(f.order.getId(), 2L, "other", null).status()).isEqualTo(CaptureResultStatus.PENDING);
        assertRows(f, "CAPTURE", 1);
        release.countDown(); first.get(20, TimeUnit.SECONDS);
        verify(gateway, times(1)).capture(any()); verify(gateway).retrievePaymentFlow(f.providerId);
    }
    @Test void simultaneousUserAndAdminCancelPostOnce() throws Exception {
        Fixture f = fixture(true);
        when(gateway.cancelAuthorization(any())).thenAnswer(call -> {
            boundary(f, "CANCEL"); if (entered.getCount() == 1) { entered.countDown(); await(release); }
            return new CancellationResult(CancellationResultStatus.PENDING, f.providerId);
        });
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(call -> {
            boundary(f, "CANCEL"); return new PaymentFlowState(f.providerId, PaymentFlowStatus.REQUIRES_CAPTURE, null, null);
        });
        Future<?> first = workers.submit(() -> cancellation.cancelForUser(f.order.getId(), f.order.getUserId(), "user"));
        await(entered);
        assertThat(adminCancellation.cancel(f.order.getId(), 1L, "admin", null).status()).isEqualTo(CancellationResultStatus.PENDING);
        assertRows(f, "CANCEL", 1);
        release.countDown(); first.get(20, TimeUnit.SECONDS);
        verify(gateway, times(1)).cancelAuthorization(any()); verify(gateway).retrievePaymentFlow(f.providerId);
    }
    @Test void captureAndCancelRejectEachOtherDuringExternalCall() throws Exception {
        when(gateway.capture(any())).thenAnswer(call -> {
            entered.countDown(); await(release);
            return new CaptureResult(CaptureResultStatus.PENDING,
                    ((CaptureRequest) call.getArgument(0)).providerPaymentId(), null, null);
        });
        when(gateway.cancelAuthorization(any())).thenAnswer(call -> {
            entered.countDown(); await(release);
            return new CancellationResult(CancellationResultStatus.PENDING,
                    ((CancellationRequest) call.getArgument(0)).providerPaymentId());
        });
        for (boolean captureFirst : new boolean[] {true, false}) {
            Fixture f = fixture(true);
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
            Future<?> first = workers.submit(() -> {
                if (captureFirst) captureAsAdmin(f.order.getId(), 1L, "admin", null);
                else adminCancellation.cancel(f.order.getId(), 1L, "admin", null);
            });
            await(entered);
            assertThatThrownBy(() -> {
                if (captureFirst) adminCancellation.cancel(f.order.getId(), 1L, "admin", null);
                else captureAsAdmin(f.order.getId(), 1L, "admin", null);
            }).isInstanceOf(captureFirst ? com.example.ecsite.exception.InvalidOrderStatusException.class
                    : IllegalStateException.class);
            release.countDown(); first.get(20, TimeUnit.SECONDS);
        }
        verify(gateway).capture(any()); verify(gateway).cancelAuthorization(any());
        verify(gateway, never()).retrievePaymentFlow(anyString());
    }
    @Test void outerTransactionsAreRejectedBeforeAnyGatewayCall() {
        Fixture f = fixture(true);
        for (Runnable operation : new Runnable[] {
                () -> authorization.prepareAuthorization(f.order),
                () -> capture.captureForShipment(f.order.getId(), 1L, "admin", null),
                () -> cancellation.cancelForUser(f.order.getId(), f.order.getUserId(), "user"),
                () -> adminCancellation.cancel(f.order.getId(), 1L, "admin", null)}) {
            assertThatThrownBy(() -> tx(() -> { operation.run(); return null; }))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }
        verifyNoInteractions(gateway);
    }
    @Test void authorizationUsesLockedLatestOrderAndRejectsCancelledOrder() {
        Fixture f = fixture(false);
        tx(() -> { orders.findByIdForUpdate(f.order.getId()).orElseThrow().cancel(LocalDateTime.now()); return null; });
        assertThatThrownBy(() -> authorization.prepareAuthorization(f.order)).isInstanceOf(IllegalStateException.class);
        assertThat(count(f, "AUTHORIZE")).isZero(); verifyNoInteractions(gateway);
    }
    @Test void decreasedAmountCaptureKeepsCurrentRevisionAndAmount() {
        Fixture f = fixture(true);
        tx(() -> { Order o = orders.findByIdForUpdate(f.order.getId()).orElseThrow(); o.setTotalAmount(800); o.incrementContentRevision(); return null; });
        startAsAdmin(f.order.getId(), 1L, "admin", null);
        assertThat(jdbc.queryForMap("select amount, order_content_revision from payment_transactions where payment_id = ? and transaction_type = 'CAPTURE'", f.paymentId))
                .containsEntry("amount", 800).containsEntry("order_content_revision", 1);
    }
    @Test void authorizationWaitsForOrderLockBeforeCreatingPayment() throws Exception {
        Fixture f = fixture(false);
        CompletableFuture<Integer> blockerPid = new CompletableFuture<>();
        Future<?> holder = workers.submit(() -> tx(() -> {
            orders.findByIdForUpdate(f.order.getId()).orElseThrow();
            blockerPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            try { await(release); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return null;
        }));
        int blocker = blockerPid.get(20, TimeUnit.SECONDS);
        when(gateway.prepareAuthorization(any())).thenAnswer(call -> {
            boundary(f, "AUTHORIZE"); return new AuthorizationPreparation("pf_locked", "secret");
        });
        Future<?> starting = workers.submit(() -> authorization.prepareAuthorization(f.order));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Integer waiter = null;
        while (System.nanoTime() < deadline) {
            var waiting = jdbc.queryForList("select pid from pg_stat_activity where datname=current_database() and ? = any(pg_blocking_pids(pid))", Integer.class, blocker);
            if (!waiting.isEmpty()) { waiter = waiting.getFirst(); break; }
            new CountDownLatch(1).await(25, TimeUnit.MILLISECONDS);
        }
        assertThat(waiter).as("a separate backend actually waits for the Order lock").isNotNull().isNotEqualTo(blocker);
        assertThat(count(f, "AUTHORIZE")).isZero();
        release.countDown(); holder.get(20, TimeUnit.SECONDS); starting.get(20, TimeUnit.SECONDS);
        assertRows(f, "AUTHORIZE", 1); verify(gateway).prepareAuthorization(any());
    }

    @Test void conflictingPendingAndChangedAuthorizationSnapshotAreRejected() {
        Fixture f = fixture(false);
        tx(() -> { paymentService.startAuthorization(orders.findByIdForUpdate(f.order.getId()).orElseThrow()); return null; });
        tx(() -> { orders.findByIdForUpdate(f.order.getId()).orElseThrow().incrementContentRevision(); return null; });
        assertThatThrownBy(() -> authorization.prepareAuthorization(f.order)).isInstanceOf(IllegalStateException.class);
        assertRows(f, "AUTHORIZE", 1); verifyNoInteractions(gateway);
        Fixture c = fixture(true);
        tx(() -> {
            Payment p = payments.findById(c.paymentId).orElseThrow();
            for (int i = 0; i < 2; i++) transactions.saveAndFlush(new PaymentTransaction(p,
                    PaymentTransactionType.CAPTURE, 1000, 0, UUID.randomUUID().toString(), LocalDateTime.now()));
            return null;
        });
        assertThatThrownBy(() -> startAsAdmin(c.order.getId(), 1L, "admin", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void existingNonPayjpPaymentAndMultipleActivePaymentsRejectAuthorization() {
        Fixture f = fixture(false);
        tx(() -> {
            transactions.saveAndFlush(new PaymentTransaction(payments.saveAndFlush(new Payment(
                    orders.findById(f.order.getId()).orElseThrow(), PaymentProvider.MOCK,
                    PaymentMethod.CARD, 1000, LocalDateTime.now())),
                    PaymentTransactionType.AUTHORIZE, 1000, 0, UUID.randomUUID().toString(), LocalDateTime.now()));
            return null;
        });
        assertThatThrownBy(() -> authorization.prepareAuthorization(f.order)).isInstanceOf(IllegalStateException.class);
        assertRows(f, "AUTHORIZE", 1);
        tx(() -> {
            Payment p = payments.saveAndFlush(new Payment(orders.findById(f.order.getId()).orElseThrow(),
                    PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, LocalDateTime.now()));
            transactions.saveAndFlush(new PaymentTransaction(p, PaymentTransactionType.AUTHORIZE,
                    1000, 0, UUID.randomUUID().toString(), LocalDateTime.now()));
            return null;
        });
        assertThatThrownBy(() -> authorization.prepareAuthorization(f.order)).isInstanceOf(IllegalStateException.class);
        assertThat(count(f, "AUTHORIZE")).isEqualTo(2);
        verifyNoInteractions(gateway);
    }

    @Test void inactivePaymentWithPendingTransactionDoesNotCreateAnotherAuthorization() {
        Fixture f = fixture(false);
        tx(() -> {
            Order o = orders.findByIdForUpdate(f.order.getId()).orElseThrow();
            paymentService.startAuthorization(o);
            Payment p = payments.findByOrderIdOrderByCreatedAtAscIdAsc(o.getId()).getFirst();
            p.markFailed(LocalDateTime.now());
            return null;
        });
        assertThatThrownBy(() -> authorization.prepareAuthorization(f.order)).isInstanceOf(IllegalStateException.class);
        assertRows(f, "AUTHORIZE", 1); verifyNoInteractions(gateway);
    }


    private void prepareCapture(Long id,Long actorId,String name) {
        tx(() -> {
            Order order=orders.findByIdForUpdate(id).orElseThrow();
            if(order.getStatus()==OrderStatus.ORDERED && order.getShippingPreparationStartedAt()==null)
                order.beginShippingPreparation(orders.findCurrentTimeInTokyo(),7L,"preparer");
            return null;
        });
        var actor=new com.example.ecsite.security.AdminUserDetails(actorId,name,"password",true,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));
    }
    private CaptureResult captureAsAdmin(Long id,Long actorId,String name,String note) {
        prepareCapture(id,actorId,name);
        try{return capture.captureForShipment(id,actorId,name,note);}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    private PaymentCaptureStart startAsAdmin(Long id,Long actorId,String name,String note) {
        prepareCapture(id,actorId,name);
        try{return captureStart.start(id,actorId,name,note);}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }

    private void boundary(Fixture f, String type) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
        assertThat(count(f, type)).isEqualTo(1);
        // Independent connection can acquire Order lock during the API call.
        tx(() -> { jdbc.execute("set local lock_timeout = '2s'"); orders.findByIdForUpdate(f.order.getId()).orElseThrow();
            if (f.paymentId != null) payments.findByIdForUpdate(f.paymentId).orElseThrow();
            return null; });
    }
    private int count(Fixture f, String type) {
        return jdbc.queryForObject("select count(*) from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? and t.transaction_type=?", Integer.class, f.order.getId(), type);
    }
    private void assertRows(Fixture f, String type, int expected) {
        assertThat(count(f, type)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id=?", Integer.class, f.order.getId())).isEqualTo(1);
    }
    private Fixture fixture(boolean authorized) {
        return tx(() -> {
            LocalDateTime now = LocalDateTime.now(); String id = UUID.randomUUID().toString();
            User u = new User(); u.setUsername("f120-" + id); u.setPassword("password"); u.setEnabled(true); u.setCreatedAt(now); u.setUpdatedAt(now); users.saveAndFlush(u);
            Order o = orders.saveAndFlush(new Order(u.getId(), 1000, now, now.plusDays(1)));
            if (!authorized) return new Fixture(o, null, null);
            Payment p = new Payment(o, PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, now);
            String provider = "pf_" + id; p.setProviderPaymentId(provider, now); p.markAuthorized(now); payments.saveAndFlush(p);
            PaymentTransaction t = new PaymentTransaction(p, PaymentTransactionType.AUTHORIZE, 1000, 0, id, now); t.markSucceeded(provider, now); transactions.saveAndFlush(t);
            return new Fixture(o, p.getId(), provider);
        });
    }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s -> action.get()); }
    private static void await(CountDownLatch latch) throws InterruptedException { assertThat(latch.await(20, TimeUnit.SECONDS)).as("controlled interleaving completed").isTrue(); }
    private record Fixture(Order order, Long paymentId, String providerId) {}
}
