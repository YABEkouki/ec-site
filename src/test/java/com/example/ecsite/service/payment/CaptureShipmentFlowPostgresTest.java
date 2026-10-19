package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.OrderStatusHistoryService;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Real service proxies and PostgreSQL. The launcher provides the isolated database; no live Gateway. */
@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false"})
@ActiveProfiles("test")
class CaptureShipmentFlowPostgresTest {
    @Autowired PaymentCaptureService capture;
    @Autowired PaymentService paymentService;
    @Autowired PaymentCaptureStartService start;
    @Autowired PaymentCaptureResultService results;
    @Autowired PaymentReconciliationItemService reconciliation;
    @Autowired PayJpWebhookCaptureSyncService webhook;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxes;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;
    @MockitoSpyBean OrderStatusHistoryService histories;
    ExecutorService workers;
    CountDownLatch release;
    @BeforeEach void setup() { workers=Executors.newFixedThreadPool(3);release=new CountDownLatch(1);authenticate(9,"shipper"); }
    @AfterEach void cleanup() throws Exception { release.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(20,TimeUnit.SECONDS)).isTrue();SecurityContextHolder.clearContext(); }
    void authenticate(long id,String name) {
        var actor=new AdminUserDetails(id,name,"password",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,"",actor.getAuthorities()));
    }
    <T>T asAdmin(Supplier<T> action) {try{authenticate(9,"shipper");return action.get();}finally{SecurityContextHolder.clearContext();}}
    <T>T tx(Supplier<T> action) {return new TransactionTemplate(manager).execute(s -> action.get());}
    LocalDateTime now() {return orders.findCurrentTimeInTokyo();}
    Fixture fixture() {
        return tx(() -> {
            var now=now();String unique=UUID.randomUUID().toString();
            User user=new User();user.setUsername("capture-flow-"+unique);user.setPassword("password");user.setEnabled(true);user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
            var cat=categories.saveAndFlush(new Category(unique));var tax=taxes.findAll().getFirst();
            Product product=new Product();product.setName("capture product");product.setPrice(100);product.setStock(10);product.setCategory(cat);product.setTaxCategory(tax);products.saveAndFlush(product);
            var ordered=now.minusDays(1).toLocalDate().atTime(10,0);
            Order order=new Order(user.getId(),200,ordered,ordered.withHour(14));order.beginShippingPreparation(ordered.withHour(14),7L,"preparer");
            order.addItem(new OrderItem(product.getId(),product.getName(),cat.getId(),cat.getName(),tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),100,2));orders.saveAndFlush(order);
            Payment payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,now);payment.setProviderPaymentId("flow_"+unique,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
            var auth=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,200,0,unique,now);auth.markSucceeded(payment.getProviderPaymentId(),now);transactions.saveAndFlush(auth);
            return new Fixture(order.getId(),payment.getId(),product.getId(),payment.getProviderPaymentId());
        });
    }
    PaymentCaptureStart begin(Fixture f) {return start.start(f.order(),9L,"shipper","memo");}
    CaptureResult captured(Fixture f) {return new CaptureResult(CaptureResultStatus.CAPTURED,f.provider(),null,null);}
    Map<String,Object> row(Fixture f) {return jdbc.queryForMap("select * from orders where id=?",f.order());}
    List<Map<String,Object>> paymentRows(Fixture f) {return jdbc.queryForList("select * from payments where order_id=? order by id",f.order());}
    List<Map<String,Object>> transactionRows(Fixture f) {return jdbc.queryForList("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.id",f.order());}
    Object invariant(Fixture f) {return List.of(jdbc.queryForMap("select total_amount,content_revision from orders where id=?",f.order()),jdbc.queryForList("select * from order_items where order_id=? order by id",f.order()),jdbc.queryForMap("select * from products where id=?",f.product()),jdbc.queryForList("select * from stock_movements where order_id=?",f.order()));}
    long historyCount(Fixture f) {return jdbc.queryForObject("select count(*) from order_status_histories where order_id=?",Long.class,f.order());}
    void boundary(Fixture f) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // An independent connection can acquire the lock and see the committed shipment and operation.
        tx(() -> {jdbc.execute("set local lock_timeout='2s'");var order=orders.findByIdForUpdate(f.order()).orElseThrow();assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);assertThat(order.getPaidAt()).isNull();assertThat(order.getShipmentCapturePaymentId()).isEqualTo(f.payment());payments.findByIdForUpdate(f.payment()).orElseThrow();return null;});
        assertThat(historyCount(f)).isEqualTo(1);assertThat(transactionRows(f)).hasSize(2);
    }
    @ParameterizedTest @ValueSource(strings={"CAPTURED","PENDING","FAILED","EXCEPTION"})
    void gatewayRunsAfterAtomicShipmentCommitAndNeverUndoesIt(String outcome) {
        Fixture f=fixture();var invariant=invariant(f);
        when(gateway.capture(any())).thenAnswer(call -> {boundary(f);if(outcome.equals("EXCEPTION"))throw new PaymentGatewayException("timeout",new RuntimeException());return new CaptureResult(CaptureResultStatus.valueOf(outcome),f.provider(),"failed","reason");});
        if(outcome.equals("EXCEPTION"))assertThatThrownBy(() -> capture.captureForShipment(f.order(),9L,"shipper","memo")).isInstanceOf(PaymentGatewayException.class);
        else capture.captureForShipment(f.order(),9L,"shipper","memo");
        assertThat(row(f)).containsEntry("status","SHIPPED").containsEntry("shipped_by_account_id",9L).containsEntry("shipped_by_username","shipper");
        assertThat(row(f).get("paid_at")!=null).isEqualTo(outcome.equals("CAPTURED"));
        assertThat(invariant(f)).isEqualTo(invariant);assertThat(historyCount(f)).isEqualTo(1);
        assertThat(transactionRows(f).getLast().get("status")).isEqualTo(outcome.equals("CAPTURED")?"SUCCEEDED":outcome.equals("FAILED")?"FAILED":"PENDING");
    }
    @Test void decreasedAmountPostsCurrentAmountWithoutRequiringAuthorizationRevisionEquality() {
        Fixture f=fixture();tx(() -> {var order=orders.findByIdForUpdate(f.order()).orElseThrow();order.setTotalAmount(100);order.incrementContentRevision();return null;});
        var before=invariant(f);when(gateway.capture(any())).thenReturn(captured(f));
        capture.captureForShipment(f.order(),9L,"shipper","memo");
        verify(gateway).capture(argThat(request -> request.amount()==100));
        assertThat(transactionRows(f).getLast()).containsEntry("amount",100).containsEntry("order_content_revision",1);
        assertThat(invariant(f)).isEqualTo(before);assertThat(row(f).get("paid_at")).isNotNull();
    }
    @Test void processStopsBeforeGatewayThenRepostOnlyGetsAndCompletedRepostDoesNothing() {
        Fixture f=fixture();var first=begin(f);var before=row(f);
        when(gateway.retrievePaymentFlow(f.provider())).thenAnswer(call -> {boundary(f);return new PaymentFlowState(f.provider(),PaymentFlowStatus.SUCCEEDED,null,null);});
        authenticate(10,"other");capture.captureForShipment(f.order(),10L,"other","changed");
        var paid=row(f).get("paid_at");assertThat(paid).isNotNull();
        capture.captureForShipment(f.order(),10L,"other","changed");
        verify(gateway,never()).capture(any());verify(gateway,times(1)).retrievePaymentFlow(f.provider());
        assertThat(row(f)).containsEntry("shipped_at",before.get("shipped_at")).containsEntry("shipped_by_account_id",9L).containsEntry("paid_at",paid);
        assertThat(transactionRows(f).getLast()).containsEntry("initiator_id",9L).containsEntry("internal_note","memo");assertThat(first.disposition()).isEqualTo(PaymentStartDisposition.NEW);
    }
    @Test void pendingGetNeverPostsOrUpdatesShipment() {
        Fixture f=fixture();begin(f);var before=row(f);var txs=transactionRows(f);
        when(gateway.retrievePaymentFlow(f.provider())).thenReturn(new PaymentFlowState(f.provider(),PaymentFlowStatus.REQUIRES_CAPTURE,null,null));
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.PENDING);
        verify(gateway,never()).capture(any());assertThat(row(f)).isEqualTo(before);assertThat(transactionRows(f)).isEqualTo(txs);
    }
    @Test void webhookWinsBeforeSynchronousResultWithoutDuplicatePaymentOrHistory() {
        Fixture f=fixture();var invariant=invariant(f);
        when(gateway.retrievePaymentFlow(f.provider())).thenReturn(new PaymentFlowState(f.provider(),PaymentFlowStatus.SUCCEEDED,null,null));
        when(gateway.capture(any())).thenAnswer(call -> {boundary(f);webhook.synchronize(f.provider());return captured(f);});
        capture.captureForShipment(f.order(),9L,"shipper","memo");
        assertThat(row(f).get("paid_at")).isNotNull();assertThat(historyCount(f)).isEqualTo(1);assertThat(invariant(f)).isEqualTo(invariant);
    }
    @Test void concurrentShipmentsSerializeAtActualOrderLockAndCreateOneOperation() throws Exception {
        Fixture f=fixture();var ready=new CompletableFuture<Integer>();
        var first=workers.submit(() -> asAdmin(() -> tx(() -> {var value=begin(f);ready.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));await(release);return value;})));
        int blocker=ready.get(20,TimeUnit.SECONDS);
        var second=workers.submit(() -> asAdmin(() -> begin(f)));blockedBy(blocker);release.countDown();
        assertThat(first.get(20,TimeUnit.SECONDS).disposition()).isEqualTo(PaymentStartDisposition.NEW);
        assertThat(second.get(20,TimeUnit.SECONDS).disposition()).isEqualTo(PaymentStartDisposition.REUSED);
        assertThat(historyCount(f)).isEqualTo(1);assertThat(transactionRows(f)).hasSize(2);verifyNoInteractions(gateway);
    }
    @Test void reconciliationAndSynchronousResultSerializeAndApplyOnlyOnce() throws Exception {
        Fixture f=fixture();var first=begin(f);var ready=new CompletableFuture<Integer>();
        var applying=workers.submit(() -> tx(() -> {var value=results.apply(first.snapshot(),captured(f));ready.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));await(release);return value;}));
        int blocker=ready.get(20,TimeUnit.SECONDS);
        when(gateway.retrievePaymentFlow(f.provider())).thenReturn(new PaymentFlowState(f.provider(),PaymentFlowStatus.SUCCEEDED,null,null));
        var reconciling=workers.submit(() -> reconciliation.reconcile(first.transactionId()));blockedBy(blocker);release.countDown();
        assertThat(applying.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentResultApplication.APPLIED);
        assertThat(reconciling.get(20,TimeUnit.SECONDS)).isEqualTo(PaymentSynchronizationOutcome.ALREADY_APPLIED);
        assertThat(historyCount(f)).isEqualTo(1);assertThat(transactionRows(f).getLast()).containsEntry("status","SUCCEEDED");
    }
    @ParameterizedTest @ValueSource(strings={"order","payment","transaction","dirty","read-only","repeatable"})
    void unsupportedResultContextsRollbackWithoutDiscardingOrChangingData(String mode) {
        Fixture f=fixture();var first=begin(f);var orderBefore=row(f);var payBefore=paymentRows(f);var txBefore=transactionRows(f);
        var template=new TransactionTemplate(manager);if(mode.equals("read-only"))template.setReadOnly(true);if(mode.equals("repeatable"))template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.executeWithoutResult(status -> {
            Session session=em.unwrap(Session.class);
            switch(mode) {
                case "order" -> session.setReadOnly(em.find(Order.class,f.order()),true);
                case "payment" -> session.setReadOnly(em.find(Payment.class,f.payment()),true);
                case "transaction" -> session.setReadOnly(em.find(PaymentTransaction.class,first.transactionId()),true);
                case "dirty" -> em.find(Order.class,f.order()).setTotalAmount(999);
            }
            assertThatThrownBy(() -> results.apply(first.snapshot(),captured(f))).isInstanceOf(IllegalStateException.class);assertThat(status.isRollbackOnly()).isTrue();status.setRollbackOnly();
        });
        assertThat(row(f)).isEqualTo(orderBefore);assertThat(paymentRows(f)).isEqualTo(payBefore);assertThat(transactionRows(f)).isEqualTo(txBefore);verifyNoInteractions(gateway);
    }
    @ParameterizedTest @ValueSource(strings={"order","payment","transaction","new-pending"})
    void previouslyLoadedEntitiesAreRefreshedBeforeResultClassification(String mode) throws Exception {
        Fixture f=fixture();var first=begin(f);var loaded=new CountDownLatch(1);var committed=new CountDownLatch(1);
        var applying=workers.submit(() -> tx(() -> {
            Order old=em.find(Order.class,f.order());em.find(Payment.class,f.payment());em.find(PaymentTransaction.class,first.transactionId());loaded.countDown();await(committed);
            var result=results.apply(first.snapshot(),captured(f));assertThat(em.find(Order.class,f.order())).isSameAs(old);return result;
        }));await(loaded);
        try{tx(() -> {orders.findByIdForUpdate(f.order()).orElseThrow();payments.findByOrderIdForUpdate(f.order());
            switch(mode) {
                case "order" -> results.apply(first.snapshot(),captured(f));
                case "payment" -> em.find(Payment.class,f.payment()).markCancelled(now());
                case "transaction" -> em.find(PaymentTransaction.class,first.transactionId()).markFailed(f.provider(),"failed","reason",now());
                case "new-pending" -> transactions.saveAndFlush(new PaymentTransaction(em.find(Payment.class,f.payment()),PaymentTransactionType.REFUND,200,0,UUID.randomUUID().toString(),now()));
            }return null;});}finally{committed.countDown();}
        assertThat(applying.get(20,TimeUnit.SECONDS)).isEqualTo(mode.equals("order")?PaymentResultApplication.ALREADY_APPLIED:PaymentResultApplication.CONFLICT);
    }
    @ParameterizedTest @ValueSource(strings={"history","outer"})
    void startFailureRollsBackOperationShipmentAndHistoryTogether(String mode) {
        Fixture f=fixture();var before=row(f);var txBefore=transactionRows(f);
        if(mode.equals("history")) {doThrow(new IllegalStateException("history failure")).when(histories).record(any(),eq(OrderStatus.ORDERED),eq(OrderStatus.SHIPPED),any(),any(),any(),any());assertThatThrownBy(() -> begin(f)).isInstanceOf(IllegalStateException.class);}
        else new TransactionTemplate(manager).executeWithoutResult(status -> {begin(f);status.setRollbackOnly();});
        assertThat(row(f)).isEqualTo(before);assertThat(transactionRows(f)).isEqualTo(txBefore);assertThat(historyCount(f)).isZero();verifyNoInteractions(gateway);
    }
    @Test void resultRollbackPreservesShipmentAndPendingOperation() {
        Fixture f=fixture();var first=begin(f);var before=row(f);var txBefore=transactionRows(f);var payBefore=paymentRows(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> {results.apply(first.snapshot(),captured(f));status.setRollbackOnly();});
        assertThat(row(f)).isEqualTo(before);assertThat(transactionRows(f)).isEqualTo(txBefore);assertThat(paymentRows(f)).isEqualTo(payBefore);
    }
    @ParameterizedTest @ValueSource(strings={"legacy-pending","legacy-shipped","failed","duplicate","wrong-target"})
    void unsafeOrLegacyDataNeverTriggersGatewayPost(String mode) {
        Fixture f=fixture();
        if(mode.equals("legacy-pending"))tx(() -> {transactions.saveAndFlush(new PaymentTransaction(em.find(Payment.class,f.payment()),PaymentTransactionType.CAPTURE,200,0,UUID.randomUUID().toString(),now()));return null;});
        else if(mode.equals("legacy-shipped"))jdbc.update("update orders set status='SHIPPED',shipped_at=localtimestamp where id=?",f.order());
        else {var first=begin(f);if(mode.equals("failed"))results.apply(first.snapshot(),new CaptureResult(CaptureResultStatus.FAILED,f.provider(),"failed","reason"));
            if(mode.equals("duplicate"))tx(() -> {transactions.saveAndFlush(new PaymentTransaction(em.find(Payment.class,f.payment()),PaymentTransactionType.CAPTURE,200,0,UUID.randomUUID().toString(),now()));return null;});
            if(mode.equals("wrong-target"))jdbc.update("update orders set shipment_capture_payment_id=null where id=?",f.order());}
        var before=row(f);var txBefore=transactionRows(f);
        assertThatThrownBy(() -> capture.captureForShipment(f.order(),9L,"shipper",null)).isInstanceOf(IllegalStateException.class);
        assertThat(row(f)).isEqualTo(before);assertThat(transactionRows(f)).isEqualTo(txBefore);verifyNoInteractions(gateway);
    }
    @Test void dbTimestampsRoundTripAtMicrosecondPrecision() {
        Fixture f=fixture();var first=begin(f);results.apply(first.snapshot(),captured(f));
        tx(() -> {var order=em.find(Order.class,f.order());assertThat(order.getShippedAt().getNano()%1000).isZero();assertThat(order.getPaidAt().getNano()%1000).isZero();assertThat(order.getPaidAt()).isAfterOrEqualTo(order.getShippedAt());return null;});
    }
    @Test void directPaymentServiceApisUseTheIdenticalProtectedOperation() {
        Fixture f=fixture();
        var first=tx(() -> paymentService.startCapture(em.find(Order.class,f.order()),
                new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,9L,"shipper","memo")));
        assertThat(row(f)).containsEntry("status","SHIPPED");assertThat(historyCount(f)).isEqualTo(1);
        assertThat(tx(() -> paymentService.applyCaptureResult(em.find(Order.class,f.order()),first.snapshot(),captured(f))))
                .isEqualTo(PaymentResultApplication.APPLIED);
        assertThat(row(f).get("paid_at")).isNotNull();verifyNoInteractions(gateway);
    }
    @ParameterizedTest @ValueSource(strings={"started","cancelled","authorization","pending"})
    void preloadedStartUsesOtherCommittedOrderPaymentAndTransactionState(String mode) throws Exception {
        Fixture f=fixture();var loaded=new CountDownLatch(1);var committed=new CountDownLatch(1);
        var starting=workers.submit(() -> asAdmin(() -> {
            try {return tx(() -> {
                var old=em.find(Order.class,f.order());em.find(Payment.class,f.payment());transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(f.payment());
                loaded.countDown();await(committed);var value=begin(f);assertThat(em.find(Order.class,f.order())).isSameAs(old);return value.disposition();
            });}catch(IllegalStateException failure){return null;}
        }));await(loaded);
        try {asAdmin(() -> tx(() -> {
            var order=orders.findByIdForUpdate(f.order()).orElseThrow();payments.findByOrderIdForUpdate(f.order());
            switch(mode) {
                case "started" -> begin(f);
                case "cancelled" -> order.cancel(now());
                case "authorization" -> em.find(Payment.class,f.payment()).markCancelled(now());
                case "pending" -> transactions.saveAndFlush(new PaymentTransaction(em.find(Payment.class,f.payment()),PaymentTransactionType.REFUND,200,0,UUID.randomUUID().toString(),now()));
            }return null;
        }));}finally{committed.countDown();}
        assertThat(starting.get(20,TimeUnit.SECONDS)).isEqualTo(mode.equals("started")?PaymentStartDisposition.REUSED:null);
        assertThat(historyCount(f)).isEqualTo(mode.equals("started")?1:0);verifyNoInteractions(gateway);
    }
    @ParameterizedTest @ValueSource(strings={"dirty","order-readonly","read-only","repeatable","db-readonly"})
    void startRejectsUnsupportedContextBeforeShipmentMutation(String mode) {
        Fixture f=fixture();var before=row(f);var txBefore=transactionRows(f);var template=new TransactionTemplate(manager);
        if(mode.equals("read-only"))template.setReadOnly(true);
        if(mode.equals("repeatable"))template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.executeWithoutResult(status -> {
            switch(mode) {
                case "dirty" -> em.find(Order.class,f.order()).setTotalAmount(999);
                case "order-readonly" -> em.unwrap(Session.class).setReadOnly(em.find(Order.class,f.order()),true);
                case "db-readonly" -> jdbc.execute("set transaction read only");
            }
            assertThatThrownBy(() -> begin(f)).isInstanceOf(IllegalStateException.class);assertThat(status.isRollbackOnly()).isTrue();status.setRollbackOnly();
        });
        assertThat(row(f)).isEqualTo(before);assertThat(transactionRows(f)).isEqualTo(txBefore);assertThat(historyCount(f)).isZero();verifyNoInteractions(gateway);
    }
    void await(CountDownLatch latch) {try{assertThat(latch.await(20,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    void blockedBy(int pid) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){if(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid))",Integer.class,pid)>0)return;new CountDownLatch(1).await(20,TimeUnit.MILLISECONDS);}throw new AssertionError("No actual PostgreSQL lock wait");}
    record Fixture(Long order,Long payment,Long product,String provider) {}
}
