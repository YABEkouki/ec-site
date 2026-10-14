package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.sql.DataSource;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.form.OrderItemChangeForm;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.pricing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentSynchronizationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature120_sync").withUsername("feature120").withPassword("feature120-test")
            .withCommand("postgres", "-c", "lock_timeout=10000", "-c", "statement_timeout=15000");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword); r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername); r.add("spring.flyway.password", POSTGRES::getPassword);
    }
    @Autowired OrderService orderService;
    @Autowired PaymentAuthorizationStartService authorizationStart;
    @Autowired PaymentCaptureStartService captureStart;
    @Autowired PaymentCancellationStartService userCancelStart;
    @Autowired AdminPaymentCancellationService adminCancel;
    @Autowired PaymentCancellationService userCancel;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxCategories;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @MockitoSpyBean com.example.ecsite.service.InventoryService inventory;
    @MockitoBean PaymentGateway gateway;
    @MockitoBean OrderAmountCalculator calculator;
    ExecutorService workers;
    CountDownLatch entered, release;
    @BeforeEach void setup() {
        workers = Executors.newFixedThreadPool(3); entered = new CountDownLatch(1); release = new CountDownLatch(1);
    }
    @AfterEach void cleanup() throws Exception {
        release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
    @Autowired PaymentReconciliationItemService reconciliation;
    @Autowired PaymentCaptureResultService captureResult;
    @Autowired PaymentAuthorizationResultService authResult;
    @Autowired PaymentCancellationResultService cancelResult;
    @Autowired AdminPaymentCancellationResultService adminCancelResult;
    @Autowired PaymentOperationSnapshotService snapshots;
    @Autowired PayJpWebhookSyncService webhook;
    @Autowired PayJpWebhookAuthorizationSyncService authWebhook;
    @Autowired PayJpWebhookCaptureSyncService captureWebhook;
    @Autowired PayJpWebhookCancellationSyncService cancelWebhook;
    @Autowired AdminPaymentCancellationStartService adminCancelStart;
    @Autowired PaymentDiscrepancyAuditRetryFacade audit;
    @Autowired com.example.ecsite.service.AdminPaymentDiscrepancyReconciliationService manualAudit;
    @Autowired PaymentDiscrepancyRepository discrepancies;
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void staleGetIsAnOrdinaryConcurrentOutcome(boolean fromWebhook) throws Exception {
        Fixture f=fixture(true);var old=captureStart.start(f.orderId,1L,"admin",null);
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(inv->{
            assertApiOutsideTransaction();entered.countDown();await(release);
            return new PaymentFlowState(f.providerId,PaymentFlowStatus.SUCCEEDED,null,null);
        });
        Future<?> work=workers.submit(()->{if(fromWebhook)webhook.synchronize("payment_flow.succeeded",f.providerId);else reconciliation.reconcile(old.transactionId());});
        try {
            await(entered);
            captureResult.apply(old.snapshot(),new CaptureResult(CaptureResultStatus.FAILED,f.providerId,"failed","failed"));
            var current=captureStart.start(f.orderId,2L,"new",null);
            release.countDown();work.get(20,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,current.transactionId())).isEqualTo("PENDING");
            assertThat(number("order_status_histories",f)).isZero();
        } finally {release.countDown();}
    }
    @Test void webhookRejectsOuterTransactionBeforeGet() {
        Fixture f=fixture(true);captureStart.start(f.orderId,1L,"admin",null);
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(inv->{assertApiOutsideTransaction();return new PaymentFlowState(f.providerId,PaymentFlowStatus.SUCCEEDED,null,null);});
        assertThatThrownBy(()->tx(()->{webhook.synchronize("payment_flow.succeeded",f.providerId);return null;}))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(gateway);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("pairs")
    void competingSynchronizersApplyEachOperationOnlyOnce(PaymentTransactionType type,String firstPath,String secondPath) throws Exception {
        Fixture f=fixture(type!=PaymentTransactionType.AUTHORIZE);var snapshot=operation(f,type);
        CountDownLatch both=new CountDownLatch(2);
        stubTerminal(f,type,()->{both.countDown();awaitUnchecked(both);awaitUnchecked(release);});
        Future<PaymentSynchronizationOutcome> first=workers.submit(()->synchronize(firstPath,snapshot));
        Future<PaymentSynchronizationOutcome> second=workers.submit(()->synchronize(secondPath,snapshot));
        try {
            await(both);release.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(PaymentSynchronizationOutcome.APPLIED,PaymentSynchronizationOutcome.ALREADY_APPLIED);
            assertTerminal(f,snapshot);verifyNoPost();
        } finally {release.countDown();}
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> pairs() {
        return java.util.stream.Stream.of(PaymentTransactionType.AUTHORIZE,PaymentTransactionType.CAPTURE,PaymentTransactionType.CANCEL)
                .flatMap(t->java.util.stream.Stream.of(
                        org.junit.jupiter.params.provider.Arguments.of(t,"webhook","reconciliation"),
                        org.junit.jupiter.params.provider.Arguments.of(t,"webhook","webhook"),
                        org.junit.jupiter.params.provider.Arguments.of(t,"reconciliation","reconciliation")));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("apiPairs")
    void apiResultCommitsWhileGetWaits(PaymentTransactionType type,String path) throws Exception {
        Fixture f=fixture(type!=PaymentTransactionType.AUTHORIZE);var snapshot=operation(f,type);
        stubTerminal(f,type,()->{entered.countDown();awaitUnchecked(release);});
        Future<PaymentSynchronizationOutcome> delayed=workers.submit(()->synchronize(path,snapshot));
        try {
            await(entered);applyApiResult(f,snapshot);assertTerminal(f,snapshot);
            release.countDown();assertThat(delayed.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentSynchronizationOutcome.ALREADY_APPLIED);
            assertTerminal(f,snapshot);verifyNoPost();
        } finally {release.countDown();}
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> apiPairs() {
        return java.util.stream.Stream.of(PaymentTransactionType.AUTHORIZE,PaymentTransactionType.CAPTURE,PaymentTransactionType.CANCEL)
                .flatMap(t->java.util.stream.Stream.of(org.junit.jupiter.params.provider.Arguments.of(t,"webhook"),org.junit.jupiter.params.provider.Arguments.of(t,"reconciliation")));
    }
    @ParameterizedTest @EnumSource(value=PaymentTransactionType.class,names={"AUTHORIZE","CAPTURE","CANCEL"})
    void repeatedDeliveredEventSkipsCompletedOperation(PaymentTransactionType type) {
        Fixture f=fixture(type!=PaymentTransactionType.AUTHORIZE);var snapshot=operation(f,type);stubTerminal(f,type,()->{});
        assertThat(synchronize("webhook",snapshot)).isEqualTo(PaymentSynchronizationOutcome.APPLIED);
        assertThat(synchronize("webhook",snapshot)).isEqualTo(PaymentSynchronizationOutcome.SKIPPED);
        assertTerminal(f,snapshot);verifyNoPost();
        if(type==PaymentTransactionType.AUTHORIZE)verify(gateway,times(1)).retrieveAuthorization(f.providerId);
        else verify(gateway,times(1)).retrievePaymentFlow(f.providerId);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"webhook","reconciliation"})
    void oldNonTerminalGetDoesNotOverwriteApiSuccess(String path) throws Exception {
        Fixture f=fixture(true);var snapshot=operation(f,PaymentTransactionType.CAPTURE);
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(inv->{assertApiOutsideTransaction();entered.countDown();await(release);return new PaymentFlowState(f.providerId,PaymentFlowStatus.REQUIRES_CAPTURE,null,null);});
        Future<PaymentSynchronizationOutcome> delayed=workers.submit(()->synchronize(path,snapshot));
        try {
            await(entered);applyApiResult(f,snapshot);release.countDown();
            assertThat(delayed.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentSynchronizationOutcome.IGNORED_STALE);
            assertTerminal(f,snapshot);
        } finally {release.countDown();}
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"webhook","reconciliation"})
    void providerChangeAfterGetIsConflictAndLeavesPending(String path) throws Exception {
        Fixture f=fixture(true);var snapshot=operation(f,PaymentTransactionType.CAPTURE);
        stubTerminal(f,PaymentTransactionType.CAPTURE,()->{entered.countDown();awaitUnchecked(release);});
        Future<PaymentSynchronizationOutcome> delayed=workers.submit(()->synchronize(path,snapshot));
        try {
            await(entered);tx(()->{payments.findByIdForUpdate(snapshot.paymentId()).orElseThrow().setProviderPaymentId("changed_"+f.providerId,LocalDateTime.now());return null;});
            release.countDown();
            if(path.equals("webhook"))assertThatThrownBy(()->delayed.get(20,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            else assertThat(delayed.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentSynchronizationOutcome.CONFLICT);
            assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,snapshot.transactionId())).isEqualTo("PENDING");
            assertThat(number("order_status_histories",f)).isZero();verifyNoPost();
        } finally {release.countDown();}
    }
    @Test void missingAuthorizationProviderIdIsSkippedWithoutResending() {
        Fixture f=fixture(false);var start=authorizationStart.start(f.orderId);var before=state(f);
        assertThat(reconciliation.reconcile(start.transactionId())).isEqualTo(PaymentSynchronizationOutcome.SKIPPED);
        assertThat(authWebhook.synchronize("payment_flow.processing",null)).isEqualTo(PaymentSynchronizationOutcome.SKIPPED);
        assertThat(state(f)).isEqualTo(before);verifyNoInteractions(gateway);
    }
    @Test void resultTransactionsLockOrderBeforePaymentOnSeparateConnections() throws Exception {
        Fixture f=fixture(true);var snapshot=operation(f,PaymentTransactionType.CAPTURE);stubTerminal(f,PaymentTransactionType.CAPTURE,()->{});
        var holderPid=new java.util.concurrent.atomic.AtomicInteger();
        Future<Integer> blocker=workers.submit(()->tx(()->{int pid=jdbc.queryForObject("select pg_backend_pid()",Integer.class);holderPid.set(pid);payments.findByIdForUpdate(snapshot.paymentId()).orElseThrow();entered.countDown();awaitUnchecked(release);return pid;}));
        try {
            await(entered);
            int blockerPid=holderPid.get();
            Future<PaymentSynchronizationOutcome> first=workers.submit(()->synchronize("webhook",snapshot));
            int firstPid=waiterOf(blockerPid);
            Future<PaymentSynchronizationOutcome> second=workers.submit(()->synchronize("reconciliation",snapshot));
            int secondPid=waiterOf(firstPid);
            assertThat(Set.of(blockerPid,firstPid,secondPid)).hasSize(3);
            release.countDown();blocker.get(20,TimeUnit.SECONDS);
            assertThat(first.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentSynchronizationOutcome.APPLIED);
            assertThat(second.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentSynchronizationOutcome.ALREADY_APPLIED);
            assertTerminal(f,snapshot);
        } finally {release.countDown();}
    }
    @Test void reconciledPaymentAllowsManualAuditToResolveExistingDiscrepancy() {
        Fixture f=fixture(true);
        Long paymentId=jdbc.queryForObject("select id from payments where order_id=?",Long.class,f.orderId);
        var flow=new PaymentFlowState(f.providerId,PaymentFlowStatus.SUCCEEDED,null,null);
        when(gateway.retrievePaymentFlow(f.providerId)).thenReturn(flow);
        var detected=audit.auditWithResult(paymentId);
        assertThat(detected.status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.INCONSISTENT);
        var snapshot=operation(f,PaymentTransactionType.CAPTURE);
        assertThat(audit.auditWithResult(paymentId).skipReason()).isEqualTo(PaymentDiscrepancyAuditResult.SkipReason.PENDING_TRANSACTION);
        stubTerminal(f,PaymentTransactionType.CAPTURE,()->{});
        assertThat(reconciliation.reconcile(snapshot.transactionId())).isEqualTo(PaymentSynchronizationOutcome.APPLIED);
        when(gateway.retrievePaymentFlow(f.providerId)).thenReturn(flow); // audit retains its existing independent Tx
        assertThat(manualAudit.reconcile(detected.discrepancyIds().getFirst()).status()).isEqualTo(PaymentDiscrepancyAuditResult.Status.CONSISTENT);
        assertThat(jdbc.queryForObject("select status from payment_discrepancies where id=?",String.class,detected.discrepancyIds().getFirst())).isEqualTo("RESOLVED");
    }
    @Test void adminCancellationUsesSavedActorAndMemoAcrossWebhookAndReconciliation() throws Exception {
        Fixture f=fixture(true);var snapshot=adminCancelStart.start(f.orderId,30L,"saved-admin","saved memo").paymentStart().snapshot();
        CountDownLatch both=new CountDownLatch(2);
        stubTerminal(f,PaymentTransactionType.CANCEL,()->{both.countDown();awaitUnchecked(both);awaitUnchecked(release);});
        Future<PaymentSynchronizationOutcome> first=workers.submit(()->synchronize("webhook",snapshot));
        Future<PaymentSynchronizationOutcome> second=workers.submit(()->synchronize("reconciliation",snapshot));
        try {
            await(both);release.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(PaymentSynchronizationOutcome.APPLIED,PaymentSynchronizationOutcome.ALREADY_APPLIED);
            assertTerminal(f,snapshot);
            assertThat(jdbc.queryForMap("select changed_by_type,changed_by_account_id,changed_by_username,internal_note from order_status_histories where order_id=?",f.orderId))
                    .containsEntry("changed_by_type","ADMIN").containsEntry("changed_by_account_id",30L)
                    .containsEntry("changed_by_username","saved-admin").containsEntry("internal_note","saved memo");
        } finally {release.countDown();}
    }
    @Test void duplicateRequiresActionDoesNotClaimAnotherStateUpdate() {
        Fixture f=fixture(false);var snapshot=operation(f,PaymentTransactionType.AUTHORIZE);
        when(gateway.retrieveAuthorization(f.providerId)).thenAnswer(inv->{assertApiOutsideTransaction();return new AuthorizationRecovery(new AuthorizationResult(AuthorizationResultStatus.REQUIRES_ACTION,null,null,null),"not-logged");});
        assertThat(synchronize("webhook",snapshot)).isEqualTo(PaymentSynchronizationOutcome.APPLIED);
        assertThat(synchronize("reconciliation",snapshot)).isEqualTo(PaymentSynchronizationOutcome.ALREADY_APPLIED);
        assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,snapshot.transactionId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from payments where id=?",String.class,snapshot.paymentId())).isEqualTo("REQUIRES_ACTION");
        assertThat(number("order_status_histories",f)).isZero();assertThat(number("stock_movements",f)).isZero();
    }
    private int waiterOf(int blocker) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline){var ids=jdbc.queryForList("select pid from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid))",Integer.class,blocker);if(!ids.isEmpty())return ids.getFirst();new CountDownLatch(1).await(25,TimeUnit.MILLISECONDS);}
        throw new AssertionError("No independent connection waiting on lock held by "+blocker);
    }
    private PaymentOperationSnapshot operation(Fixture f,PaymentTransactionType type) {
        return switch(type) {
            case AUTHORIZE -> {var start=authorizationStart.start(f.orderId);authResult.setProviderPaymentId(start.snapshot(),f.providerId);yield snapshots.find(start.transactionId()).orElseThrow();}
            case CAPTURE -> captureStart.start(f.orderId,1L,"admin","memo").snapshot();
            case CANCEL -> userCancelStart.start(f.orderId,f.userId,"saved-user").snapshot();
            default -> throw new AssertionError(type);
        };
    }
    private PaymentSynchronizationOutcome synchronize(String path,PaymentOperationSnapshot snapshot) {
        if(path.equals("reconciliation"))return reconciliation.reconcile(snapshot.transactionId());
        return switch(snapshot.type()) {
            case AUTHORIZE -> authWebhook.synchronize("payment_flow.amount_capturable_updated",snapshot.providerPaymentId());
            case CAPTURE -> captureWebhook.synchronize(snapshot.providerPaymentId());
            case CANCEL -> cancelWebhook.synchronize(snapshot.providerPaymentId());
            default -> throw new AssertionError(snapshot.type());
        };
    }
    private void stubTerminal(Fixture f,PaymentTransactionType type,Runnable waiting) {
        if(type==PaymentTransactionType.AUTHORIZE)when(gateway.retrieveAuthorization(f.providerId)).thenAnswer(inv->{assertApiOutsideTransaction();waiting.run();return new AuthorizationRecovery(new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,null,null,null),null);});
        else when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(inv->{assertApiOutsideTransaction();waiting.run();return new PaymentFlowState(f.providerId,type==PaymentTransactionType.CAPTURE?PaymentFlowStatus.SUCCEEDED:PaymentFlowStatus.CANCELED,null,null);});
    }
    private void applyApiResult(Fixture f,PaymentOperationSnapshot snapshot) {
        var applied=switch(snapshot.type()){
            case AUTHORIZE -> authResult.apply(snapshot,new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,null,null,null));
            case CAPTURE -> captureResult.apply(snapshot,new CaptureResult(CaptureResultStatus.CAPTURED,f.providerId,null,null));
            case CANCEL -> cancelResult.apply(snapshot,new CancellationResult(CancellationResultStatus.CANCELLED,f.providerId));
            default -> throw new AssertionError(snapshot.type());
        };assertThat(applied).isEqualTo(PaymentResultApplication.APPLIED);
    }
    private void assertTerminal(Fixture f,PaymentOperationSnapshot snapshot) {
        assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,snapshot.transactionId())).isEqualTo("SUCCEEDED");
        assertThat(number("order_status_histories",f)).isEqualTo(snapshot.type()==PaymentTransactionType.AUTHORIZE?0:1);
        assertThat(number("stock_movements",f)).isEqualTo(snapshot.type()==PaymentTransactionType.CANCEL?2:0);
        assertThat(stock(f)).containsExactly(snapshot.type()==PaymentTransactionType.CANCEL?12:10,snapshot.type()==PaymentTransactionType.CANCEL?12:10);
    }
    private void verifyNoPost(){verify(gateway,never()).prepareAuthorization(any());verify(gateway,never()).capture(any());verify(gateway,never()).cancelAuthorization(any());}
    private void assertApiOutsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }
    private boolean change(Fixture f) { return orderService.changeItemsForUser(f.orderId,f.userId,"user",form(f)); }
    private OrderItemChangeForm form(Fixture f) {
        OrderItemChangeForm form=new OrderItemChangeForm(); form.setContentRevision(0);
        for(int i=0;i<2;i++) { var item=new OrderItemChangeForm.Item();item.setOrderItemId(f.itemIds.get(i));item.setQuantity(i==0?1:0);form.getItems().add(item); }
        return form;
    }
    private void price(Fixture f,int amount) { when(calculator.calculate(any(),any(ChargeTaxSnapshot.class))).thenReturn(amount(f,amount)); }
    private OrderAmount amount(Fixture f,int total) { return new OrderAmount(total,List.of(new OrderChargeAmount(OrderChargeType.SHIPPING,"shipping",0,f.taxId,"test","test",BigDecimal.ZERO,1)),0,0,total); }
    private List<Integer> stock(Fixture f) { return jdbc.queryForList("select stock from products where id in (?,?) order by id",Integer.class,f.productIds.get(0),f.productIds.get(1)); }
    private int number(String table,Fixture f) { return jdbc.queryForObject("select count(*) from "+table+" where order_id=?",Integer.class,f.orderId); }
    private Map<String,Object> state(Fixture f) {
        Map<String,Object> state=new LinkedHashMap<>();
        state.put("order",jdbc.queryForMap("select * from orders where id=?",f.orderId));
        state.put("items",jdbc.queryForList("select * from order_items where order_id=? order by id",f.orderId));
        state.put("products",jdbc.queryForList("select * from products where id in (?,?) order by id",f.productIds.get(0),f.productIds.get(1)));
        state.put("charges",jdbc.queryForList("select * from order_charges where order_id=? order by id",f.orderId));
        for(String table:List.of("order_content_change_histories","stock_movements","order_status_histories")) {
            state.put(table,jdbc.queryForList("select * from "+table+" where order_id=? order by id",f.orderId));
        }
        return state;
    }
    @Autowired PaymentAuthorizationService authorizationApi;
    @Autowired PaymentCaptureService captureApi;
    @Autowired PaymentPostGuardService postGuard;

    @ParameterizedTest @EnumSource(value=PaymentGatewayErrorCategory.class)
    void uncertainAuthorizationKeepsSinglePendingAndStopsProviderlessResume(PaymentGatewayErrorCategory category) {
        Fixture f=fixture(false);
        when(gateway.prepareAuthorization(any())).thenAnswer(inv -> {
            assertApiOutsideTransaction();
            assertThat(jdbc.queryForObject("select count(*) from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? and t.status='PENDING'",Integer.class,f.orderId)).isEqualTo(1);
            throw new PaymentGatewayException(category,409,"test",true);
        });
        var order=orders.findById(f.orderId).orElseThrow();
        assertThatThrownBy(()->authorizationApi.prepareAuthorization(order)).isInstanceOf(PaymentGatewayException.class);
        var original=jdbc.queryForMap("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=?",f.orderId);
        assertThatThrownBy(()->authorizationApi.prepareAuthorization(order)).isInstanceOf(PaymentSafetyStopException.class);
        assertThat(jdbc.queryForMap("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=?",f.orderId)).isEqualTo(original);
        assertThat(original.get("status")).isEqualTo("PENDING");
        assertThat(number("order_status_histories",f)).isZero();
        verify(gateway,times(1)).prepareAuthorization(any());verify(gateway,never()).retrieveAuthorization(anyString());
    }
    @ParameterizedTest @EnumSource(value=PaymentTransactionType.class,names={"CAPTURE","CANCEL"})
    void uncertainPostRecoversByGetWithOriginalTransaction(PaymentTransactionType type) {
        Fixture f=fixture(true);
        var failure=new PaymentGatewayException(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN,null,null,true);
        if(type==PaymentTransactionType.CAPTURE) when(gateway.capture(any())).thenAnswer(inv->{assertApiOutsideTransaction();throw failure;});
        else when(gateway.cancelAuthorization(any())).thenAnswer(inv->{assertApiOutsideTransaction();throw failure;});
        assertThatThrownBy(()->{if(type==PaymentTransactionType.CAPTURE)captureApi.captureForShipment(f.orderId,1L,"admin",null);else userCancel.cancelForUser(f.orderId,f.userId,"user");}).isSameAs(failure);
        var pending=jdbc.queryForMap("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? and t.transaction_type=?",f.orderId,type.name());
        assertThat(pending.get("status")).isEqualTo("PENDING");
        assertThat(number("order_status_histories",f)).isZero();
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(inv->{assertApiOutsideTransaction();return new PaymentFlowState(f.providerId,type==PaymentTransactionType.CAPTURE?PaymentFlowStatus.SUCCEEDED:PaymentFlowStatus.CANCELED,null,null);});
        if(type==PaymentTransactionType.CAPTURE)captureApi.captureForShipment(f.orderId,1L,"admin",null);else userCancel.cancelForUser(f.orderId,f.userId,"user");
        var current=jdbc.queryForMap("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? and t.transaction_type=?",f.orderId,type.name());
        for(String key:List.of("id","amount","order_content_revision","idempotency_key"))assertThat(current.get(key)).isEqualTo(pending.get(key));
        assertThat(current.get("status")).isEqualTo("SUCCEEDED");
        assertThat(number("order_status_histories",f)).isEqualTo(1);
        if(type==PaymentTransactionType.CAPTURE)verify(gateway,times(1)).capture(any());else verify(gateway,times(1)).cancelAuthorization(any());
    }
    @Test void delayedNewSnapshotStopsBeforePostWithoutChangingPending() {
        Fixture f=fixture(true);var start=captureStart.start(f.orderId,1L,"admin",null);
        jdbc.update("update payment_transactions set created_at=created_at - interval '24 hours' where id=?",start.transactionId());
        assertThatThrownBy(()->postGuard.beforeNewPost(start.snapshot())).isInstanceOf(PaymentSafetyStopException.class);
        assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,start.transactionId())).isEqualTo("PENDING");
        verifyNoPost();
    }
    @MockitoSpyBean PaymentService paymentServiceSpy;
    @Test void successfulAuthorizationPostThenProviderSaveRollbackStopsFuturePost() {
        Fixture f=fixture(false);
        when(gateway.prepareAuthorization(any())).thenAnswer(inv->{assertApiOutsideTransaction();return new AuthorizationPreparation(f.providerId,"private-client-secret");});
        doAnswer(inv->{
            Object result=inv.callRealMethod();
            throw new org.springframework.dao.DataAccessResourceFailureException("simulated DB failure");
        }).when(paymentServiceSpy).setProviderPaymentId(any(Order.class),any(PaymentOperationSnapshot.class),eq(f.providerId));
        var order=orders.findById(f.orderId).orElseThrow();
        assertThatThrownBy(()->authorizationApi.prepareAuthorization(order)).isInstanceOf(PaymentSafetyStopException.class);
        assertThat(jdbc.queryForObject("select provider_payment_id from payments where order_id=?",String.class,f.orderId)).isNull();
        assertThat(jdbc.queryForObject("select status from payments where order_id=?",String.class,f.orderId)).isEqualTo("PENDING");
        assertThatThrownBy(()->authorizationApi.prepareAuthorization(order)).isInstanceOf(PaymentSafetyStopException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=?",Integer.class,f.orderId)).isEqualTo(1);
        verify(gateway,times(1)).prepareAuthorization(any());
        assertThat(number("stock_movements",f)).isZero();
    }
    private Fixture fixture(boolean authorized) {
        return tx(() -> {
            String unique=UUID.randomUUID().toString();LocalDateTime now=LocalDateTime.now();
            User u=new User();u.setUsername("f120-"+unique);u.setPassword("password");u.setEnabled(true);u.setCreatedAt(now);u.setUpdatedAt(now);users.saveAndFlush(u);
            Category c=categories.saveAndFlush(new Category("f120-"+unique));TaxCategory tax=taxCategories.findAll().getFirst();
            Order o=new Order(u.getId(),3000,now,now.plusDays(1));List<Long> productIds=new ArrayList<>();
            for(int price:List.of(1000,500)) {
                Product p=new Product();p.setName("fixture");p.setPrice(price);p.setStock(10);p.setCategory(c);p.setTaxCategory(tax);products.saveAndFlush(p);productIds.add(p.getId());
                o.addItem(new OrderItem(p.getId(),p.getName(),c.getId(),c.getName(),tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),price,2));
            }
            o.addCharge(new OrderCharge(OrderChargeType.SHIPPING,"shipping",0,tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),1));
            o.applyAmount(amount(new Fixture(null,null,null,null,null,tax.getId()),3000));
            orders.saveAndFlush(o);String provider="pf_"+unique;
            if(authorized) createAuthorized(o,provider);
            return new Fixture(o.getId(),u.getId(),provider,productIds,o.getItems().stream().map(OrderItem::getId).toList(),tax.getId());
        });
    }
    private void createAuthorized(Order o,String provider) {
        LocalDateTime now=LocalDateTime.now();Payment p=new Payment(o,PaymentProvider.PAYJP,PaymentMethod.CARD,3000,now);p.setProviderPaymentId(provider,now);p.markAuthorized(now);payments.saveAndFlush(p);
        PaymentTransaction t=new PaymentTransaction(p,PaymentTransactionType.AUTHORIZE,3000,0,UUID.randomUUID().toString(),now);t.markSucceeded(provider,now);transactions.saveAndFlush(t);
    }
    private <T>T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s -> action.get()); }
    private static void await(CountDownLatch latch)throws InterruptedException { assertThat(latch.await(20,TimeUnit.SECONDS)).as("controlled interleaving").isTrue(); }
    private static void awaitUnchecked(CountDownLatch latch) { try { await(latch); }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);} }
    private record Fixture(Long orderId,Long userId,String providerId,List<Long> productIds,List<Long> itemIds,Long taxId) {}
}
