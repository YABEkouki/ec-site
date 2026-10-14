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
class PaymentResultApplicationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature120_results").withUsername("feature120").withPassword("feature120-test")
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
        workers = Executors.newFixedThreadPool(2); entered = new CountDownLatch(1); release = new CountDownLatch(1);
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
    @Test void reconciliationGetRunsWithoutTransactionAndCanAcquireOrderLock() {
        Fixture f=fixture(true); PaymentCaptureStart start=captureStart.start(f.orderId,1L,"admin",null);
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
            tx(() -> { jdbc.execute("set local lock_timeout='2s'"); orders.findByIdForUpdate(f.orderId).orElseThrow(); return null; });
            return new PaymentFlowState(f.providerId,PaymentFlowStatus.SUCCEEDED,null,null);
        });
        reconciliation.reconcile(start.transactionId());
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.orderId)).isEqualTo("SHIPPED");
    }
    @Test void captureRevisionMismatchDoesNotShipOrWriteHistory() {
        Fixture f=fixture(true); PaymentCaptureStart start=captureStart.start(f.orderId,1L,"admin",null);
        jdbc.update("update orders set content_revision=content_revision+1 where id=?",f.orderId);
        captureResult.apply(start.snapshot(),new CaptureResult(CaptureResultStatus.CAPTURED,f.providerId,null,null));
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.orderId)).isEqualTo("ORDERED");
        assertThat(number("order_status_histories",f)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"orderId","paymentId","transactionId","type","idempotencyKey","amount","orderContentRevision","providerPaymentId"})
    void captureRejectsMismatchedIdentityAndRequestValues(String field) {
        Fixture f=fixture(true), other=fixture(true);
        PaymentCaptureStart start=captureStart.start(f.orderId,1L,"admin",null);
        PaymentCaptureStart otherStart=captureStart.start(other.orderId,1L,"other",null);
        var original=start.snapshot();
        Object value=switch(field) {
            case "orderId" -> other.orderId;
            case "paymentId" -> otherStart.paymentId();
            case "transactionId" -> otherStart.transactionId();
            case "type" -> PaymentTransactionType.CANCEL;
            case "amount" -> original.amount()+1;
            case "orderContentRevision" -> original.orderContentRevision()+1;
            default -> "different";
        };
        var before=state(f); var otherBefore=state(other);
        assertThat(captureResult.apply(replace(original,field,value),captured(f))).isEqualTo(PaymentResultApplication.CONFLICT);
        assertThat(state(f)).isEqualTo(before);assertThat(state(other)).isEqualTo(otherBefore);
        assertThat(txnStatus(start.transactionId())).isEqualTo("PENDING");
    }
    @Test void missingTransactionIsRejectedWithoutSideEffects() {
        Fixture f=fixture(true);var start=captureStart.start(f.orderId,1L,"admin",null);var before=state(f);
        assertThat(captureResult.apply(replace(start.snapshot(),"transactionId",Long.MAX_VALUE),captured(f))).isEqualTo(PaymentResultApplication.CONFLICT);
        assertThat(state(f)).isEqualTo(before);
    }
    @Test void sameCaptureResultShipsOnlyOnce() {
        Fixture f=fixture(true);var start=captureStart.start(f.orderId,1L,"saved-admin","memo");
        assertThat(captureResult.apply(start.snapshot(),captured(f))).isEqualTo(PaymentResultApplication.APPLIED);
        var after=state(f);
        assertThat(captureResult.apply(start.snapshot(),captured(f))).isEqualTo(PaymentResultApplication.ALREADY_APPLIED);
        assertThat(state(f)).isEqualTo(after);assertThat(number("order_status_histories",f)).isEqualTo(1);
        assertThat(number("stock_movements",f)).isZero();assertThat(stock(f)).containsExactly(10,10);
    }
    @Test void captureAmountMismatchDoesNotShip() {
        Fixture f=fixture(true);var start=captureStart.start(f.orderId,1L,"admin",null);
        jdbc.update("update orders set total_amount=total_amount-1 where id=?",f.orderId);var before=state(f);
        assertThat(captureResult.apply(start.snapshot(),captured(f))).isEqualTo(PaymentResultApplication.CONFLICT);
        assertThat(state(f)).isEqualTo(before);assertThat(txnStatus(start.transactionId())).isEqualTo("PENDING");
    }
    @Test void decreasedCaptureUsesCaptureRevisionAndAmount() {
        Fixture f=fixture(true); price(f,1000);change(f);var start=captureStart.start(f.orderId,1L,"admin",null);
        assertThat(start.snapshot().amount()).isEqualTo(1000);assertThat(start.snapshot().orderContentRevision()).isEqualTo(1);
        assertThat(captureResult.apply(start.snapshot(),captured(f))).isEqualTo(PaymentResultApplication.APPLIED);
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.orderId)).isEqualTo("SHIPPED");
    }
    @Test void sameUserCancelRestoresStockAndRecordsSavedActorOnlyOnce() {
        Fixture f=fixture(true);var start=userCancelStart.start(f.orderId,f.userId,"saved-user");
        var result=new CancellationResult(CancellationResultStatus.CANCELLED,f.providerId);
        assertThat(cancelResult.apply(start.snapshot(),result)).isEqualTo(PaymentResultApplication.APPLIED);var after=state(f);
        assertThat(cancelResult.apply(start.snapshot(),result)).isEqualTo(PaymentResultApplication.ALREADY_APPLIED);
        assertThat(state(f)).isEqualTo(after);assertThat(stock(f)).containsExactly(12,12);
        assertThat(number("order_status_histories",f)).isEqualTo(1);assertThat(number("stock_movements",f)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select changed_by_username from order_status_histories where order_id=?",String.class,f.orderId)).isEqualTo("saved-user");
    }
    @Test void cancellationRejectsProviderConflictAndPreservesPending() {
        Fixture f=fixture(true);var start=userCancelStart.start(f.orderId,f.userId,"user");var before=state(f);
        assertThat(cancelResult.apply(start.snapshot(),new CancellationResult(CancellationResultStatus.CANCELLED,"different")))
                .isEqualTo(PaymentResultApplication.CONFLICT);
        assertThat(state(f)).isEqualTo(before);assertThat(txnStatus(start.transactionId())).isEqualTo("PENDING");
    }
    @ParameterizedTest @EnumSource(value=AuthorizationResultStatus.class,names={"AUTHORIZED","FAILED"})
    void authorizationTerminalResultIsAppliedExactlyOnce(AuthorizationResultStatus status) {
        Fixture f=fixture(false);var start=authorizationStart.start(f.orderId);
        assertThat(authResult.setProviderPaymentId(start.snapshot(),f.providerId)).isEqualTo(PaymentResultApplication.APPLIED);
        var snapshot=snapshots.find(start.transactionId()).orElseThrow();
        var result=new AuthorizationResult(status,null,"code","message");
        assertThat(authResult.apply(snapshot,result)).isEqualTo(PaymentResultApplication.APPLIED);var after=state(f);
        assertThat(authResult.apply(snapshot,result)).isEqualTo(PaymentResultApplication.ALREADY_APPLIED);
        assertThat(state(f)).isEqualTo(after);
        assertThat(number("order_status_histories",f)).isEqualTo(status==AuthorizationResultStatus.FAILED?1:0);
        assertThat(number("stock_movements",f)).isEqualTo(status==AuthorizationResultStatus.FAILED?2:0);
    }
    @Test void providerIdAssignmentIsIdempotentAndRejectsDifferentId() {
        Fixture f=fixture(false);var start=authorizationStart.start(f.orderId);
        assertThat(authResult.setProviderPaymentId(start.snapshot(),f.providerId)).isEqualTo(PaymentResultApplication.APPLIED);
        assertThat(authResult.setProviderPaymentId(start.snapshot(),f.providerId)).isEqualTo(PaymentResultApplication.ALREADY_APPLIED);
        assertThat(authResult.setProviderPaymentId(start.snapshot(),"different")).isEqualTo(PaymentResultApplication.CONFLICT);
        assertThat(jdbc.queryForObject("select provider_payment_id from payments where id=?",String.class,start.paymentId())).isEqualTo(f.providerId);
        assertThatThrownBy(()->authResult.setProviderPaymentId(start.snapshot()," ")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oldCaptureSuccessCannotUpdateNewPendingOperation() {
        Fixture f=fixture(true);var old=captureStart.start(f.orderId,1L,"old",null);
        assertThat(captureResult.apply(old.snapshot(),new CaptureResult(CaptureResultStatus.FAILED,f.providerId,"failed","failed")))
                .isEqualTo(PaymentResultApplication.APPLIED);
        var current=captureStart.start(f.orderId,2L,"new",null);var before=state(f);
        assertThat(captureResult.apply(old.snapshot(),captured(f))).isEqualTo(PaymentResultApplication.IGNORED_STALE);
        assertThat(state(f)).isEqualTo(before);assertThat(txnStatus(current.transactionId())).isEqualTo("PENDING");
    }
    @Test void completedCaptureFailureWithNewPendingIsStale() {
        Fixture f=fixture(true);var old=captureStart.start(f.orderId,1L,"old",null);
        var failure=new CaptureResult(CaptureResultStatus.FAILED,f.providerId,"failed","failed");captureResult.apply(old.snapshot(),failure);
        captureStart.start(f.orderId,2L,"new",null);
        assertThat(captureResult.apply(old.snapshot(),failure)).isEqualTo(PaymentResultApplication.IGNORED_STALE);
    }
    @Test void resultRollbackAndRetryAreAtomic() {
        Fixture f=fixture(true);var start=captureStart.start(f.orderId,1L,"admin",null);var before=state(f);
        assertThatThrownBy(()->tx(()->{captureResult.apply(start.snapshot(),captured(f));throw new IllegalStateException("rollback");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state(f)).isEqualTo(before);assertThat(txnStatus(start.transactionId())).isEqualTo("PENDING");
        assertThat(captureResult.apply(start.snapshot(),captured(f))).isEqualTo(PaymentResultApplication.APPLIED);
        assertThat(number("order_status_histories",f)).isEqualTo(1);
    }
    @Test void reconciliationWaitDoesNotApplyOldRevision() throws Exception {
        Fixture f=fixture(true);var start=captureStart.start(f.orderId,1L,"admin",null);
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(inv->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();await(release);
            return new PaymentFlowState(f.providerId,PaymentFlowStatus.SUCCEEDED,null,null);
        });
        Future<?> work=workers.submit(()->reconciliation.reconcile(start.transactionId()));await(entered);
        tx(()->{jdbc.update("update orders set content_revision=content_revision+1 where id=?",f.orderId);return null;});
        var before=state(f);release.countDown();assertThat(work.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentSynchronizationOutcome.CONFLICT);
        assertThat(state(f)).isEqualTo(before);assertThat(txnStatus(start.transactionId())).isEqualTo("PENDING");
    }
    @Test void duplicateResultsOnIndependentConnectionsSerializeAtOrderLock() throws Exception {
        Fixture f=fixture(true);var start=captureStart.start(f.orderId,1L,"admin",null);
        Future<?> first=workers.submit(()->tx(()->{orders.findByIdForUpdate(f.orderId).orElseThrow();entered.countDown();awaitUnchecked(release);return captureResult.apply(start.snapshot(),captured(f));}));
        await(entered);
        Future<PaymentResultApplication> second=workers.submit(()->captureResult.apply(start.snapshot(),captured(f)));
        // The second connection must wait on the same order, while the first holds it.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean waiting=false;
        while(System.nanoTime()<deadline) {
            if(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and cardinality(pg_blocking_pids(pid))>0",Integer.class)>0){waiting=true;break;}
            new CountDownLatch(1).await(25,TimeUnit.MILLISECONDS);
        }
        assertThat(waiting).isTrue();release.countDown();first.get(20,TimeUnit.SECONDS);
        assertThat(second.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentResultApplication.ALREADY_APPLIED);
        assertThat(number("order_status_histories",f)).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(value=PaymentTransactionType.class,names={"AUTHORIZE","CAPTURE","CANCEL"})
    void reconciliationNormalPathsCommitInFreshResultTransaction(PaymentTransactionType type) {
        Fixture f=fixture(type!=PaymentTransactionType.AUTHORIZE);Long id;
        if(type==PaymentTransactionType.AUTHORIZE){var start=authorizationStart.start(f.orderId);authResult.setProviderPaymentId(start.snapshot(),f.providerId);id=start.transactionId();}
        else if(type==PaymentTransactionType.CAPTURE)id=captureStart.start(f.orderId,1L,"admin",null).transactionId();
        else id=userCancelStart.start(f.orderId,f.userId,"user").transactionId();
        when(gateway.retrieveAuthorization(f.providerId)).thenAnswer(inv->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new AuthorizationRecovery(new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED,null,null,null),null);});
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(inv->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new PaymentFlowState(f.providerId,type==PaymentTransactionType.CAPTURE?PaymentFlowStatus.SUCCEEDED:PaymentFlowStatus.CANCELED,null,null);});
        reconciliation.reconcile(id);assertThat(txnStatus(id)).isEqualTo("SUCCEEDED");
    }
    @Test void sameProviderIdDoesNotAcceptObsoleteAuthorizationSnapshot() {
        Fixture f=fixture(false);var start=authorizationStart.start(f.orderId);
        authResult.setProviderPaymentId(start.snapshot(),f.providerId);
        jdbc.update("update orders set content_revision=content_revision+1 where id=?",f.orderId);
        assertThat(authResult.setProviderPaymentId(start.snapshot(),f.providerId)).isEqualTo(PaymentResultApplication.IGNORED_STALE);
    }
    @ParameterizedTest @ValueSource(strings={"provider","key","amount"})
    void reconciliationReloadsPaymentAndTransactionAfterGet(String changed) {
        Fixture f=fixture(true);var start=captureStart.start(f.orderId,1L,"admin",null);
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(inv->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            tx(()->{
                if(changed.equals("provider"))jdbc.update("update payments set provider_payment_id=? where id=?","changed_"+f.providerId,start.paymentId());
                else if(changed.equals("key"))jdbc.update("update payment_transactions set idempotency_key=? where id=?",UUID.randomUUID().toString(),start.transactionId());
                else jdbc.update("update payment_transactions set amount=amount+1 where id=?",start.transactionId());
                return null;
            });
            return new PaymentFlowState(f.providerId,PaymentFlowStatus.SUCCEEDED,null,null);
        });
        assertThat(reconciliation.reconcile(start.transactionId())).isEqualTo(PaymentSynchronizationOutcome.CONFLICT);
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.orderId)).isEqualTo("ORDERED");
        assertThat(txnStatus(start.transactionId())).isEqualTo("PENDING");assertThat(number("order_status_histories",f)).isZero();
    }
    @Test void oldCaptureAndCurrentCancelResultsSerializeWithoutInvalidTransition() throws Exception {
        Fixture f=fixture(true);var old=captureStart.start(f.orderId,1L,"admin",null);
        captureResult.apply(old.snapshot(),new CaptureResult(CaptureResultStatus.FAILED,f.providerId,"failed","failed"));
        var current=userCancelStart.start(f.orderId,f.userId,"user");
        CountDownLatch ready=new CountDownLatch(2);
        Future<PaymentResultApplication> stale=workers.submit(()->{ready.countDown();await(ready);return captureResult.apply(old.snapshot(),captured(f));});
        Future<PaymentResultApplication> cancel=workers.submit(()->{ready.countDown();await(ready);return cancelResult.apply(current.snapshot(),new CancellationResult(CancellationResultStatus.CANCELLED,f.providerId));});
        assertThat(stale.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentResultApplication.IGNORED_STALE);
        assertThat(cancel.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentResultApplication.APPLIED);
        assertThat(number("order_status_histories",f)).isEqualTo(1);assertThat(number("stock_movements",f)).isEqualTo(2);
        assertThat(stock(f)).containsExactly(12,12);
    }
    @Test void reconciliationRejectsOuterTransactionBeforeGet() {
        Fixture f=fixture(true);var start=captureStart.start(f.orderId,1L,"admin",null);
        assertThatThrownBy(()->tx(()->{reconciliation.reconcile(start.transactionId());return null;}))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(gateway);
    }
    @Test void reconciliationResultRollbackRestoresAllSideEffectsAndRetryCommits() {
        Fixture f=fixture(true);var start=userCancelStart.start(f.orderId,f.userId,"saved-user");var before=state(f);
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(inv->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PaymentFlowState(f.providerId,PaymentFlowStatus.CANCELED,null,null);
        });
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("injected after stock write");})
                .when(inventory).restoreForOrderCancellation(anyLong(),anyInt(),eq(f.orderId));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThatThrownBy(()->reconciliation.reconcile(start.transactionId())).isInstanceOf(IllegalStateException.class);
        assertThat(state(f)).isEqualTo(before);assertThat(txnStatus(start.transactionId())).isEqualTo("PENDING");
        doCallRealMethod().when(inventory).restoreForOrderCancellation(anyLong(),anyInt(),eq(f.orderId));
        reconciliation.reconcile(start.transactionId());
        assertThat(txnStatus(start.transactionId())).isEqualTo("SUCCEEDED");assertThat(number("order_status_histories",f)).isEqualTo(1);
        assertThat(number("stock_movements",f)).isEqualTo(2);assertThat(stock(f)).containsExactly(12,12);
        verify(gateway,times(2)).retrievePaymentFlow(f.providerId);verify(gateway,never()).cancelAuthorization(any());
    }
    @Test void oldCancelResultDoesNotOverwriteCurrentCapture() {
        Fixture f=fixture(true);var old=userCancelStart.start(f.orderId,f.userId,"old");
        tx(()->{transactions.findById(old.transactionId()).orElseThrow().markFailed(null,"failed","failed",LocalDateTime.now());return null;});
        var current=captureStart.start(f.orderId,1L,"admin",null);
        assertThat(cancelResult.apply(old.snapshot(),new CancellationResult(CancellationResultStatus.CANCELLED,f.providerId)))
                .isEqualTo(PaymentResultApplication.IGNORED_STALE);
        assertThat(captureResult.apply(current.snapshot(),captured(f))).isEqualTo(PaymentResultApplication.APPLIED);
        assertThat(number("order_status_histories",f)).isEqualTo(1);assertThat(number("stock_movements",f)).isZero();
    }
    private CaptureResult captured(Fixture f){return new CaptureResult(CaptureResultStatus.CAPTURED,f.providerId,null,null);}
    private String txnStatus(Long id){return jdbc.queryForObject("select status from payment_transactions where id=?",String.class,id);}
    private PaymentOperationSnapshot replace(PaymentOperationSnapshot original,String field,Object value) {
        try {
            var fields=PaymentOperationSnapshot.class.getRecordComponents();Object[] values=new Object[fields.length];Class<?>[] types=new Class<?>[fields.length];
            for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();values[i]=fields[i].getName().equals(field)?value:fields[i].getAccessor().invoke(original);}
            return PaymentOperationSnapshot.class.getDeclaredConstructor(types).newInstance(values);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
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
