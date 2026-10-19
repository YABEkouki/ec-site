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
@com.example.ecsite.support.CaptureConnectionTest
class CaptureShipmentFlowPostgresTest {
    @Autowired PaymentCaptureService capture;
    @Autowired AdminCaptureRequeryService requery;
    @Autowired AdminCaptureRequeryPreparationService requeryPreparation;
    @Autowired PaymentService paymentService;
    @Autowired PaymentCaptureStartService start;
    @Autowired PaymentCaptureResultService results;
    @Autowired CaptureInitialPostPreparationService initialPreparation;
    @MockitoSpyBean com.example.ecsite.payment.capture.CaptureRequestStore requestStore;
    @MockitoSpyBean com.example.ecsite.config.CaptureConnectionProperties connection;
    @MockitoSpyBean com.example.ecsite.config.PayJpSafetyProperties safety;
    @Autowired PaymentReconciliationItemService reconciliation;
    @Autowired PayJpWebhookCaptureSyncService webhook;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @MockitoSpyBean PaymentTransactionRepository transactions;
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
            Payment payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,now);payment.setProviderPaymentId("pfw_flow_"+unique,now);payment.markAuthorized(now);payments.saveAndFlush(payment);
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
        assertThat(jdbc.queryForObject("select count(*) from capture_requests where payment_id=?",Integer.class,f.payment())).isEqualTo(1);
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
    @Test void newStartAtomicallyPersistsTheExactCaptureRequest() {
        Fixture f=fixture();var first=begin(f);
        var saved=jdbc.queryForMap("select * from capture_requests where capture_transaction_id=?",first.transactionId());
        assertThat(saved).containsEntry("payment_id",f.payment()).containsEntry("provider_flow_id",f.provider());
        assertThat(saved.get("request_body")).isEqualTo("{\"amount_to_capture\":200}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(row(f)).containsEntry("status","SHIPPED");assertThat(historyCount(f)).isEqualTo(1);
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
    @ParameterizedTest @ValueSource(strings={"SUCCEEDED","PROCESSING","REQUIRES_CAPTURE","CANCELED","EXCEPTION","TIMEOUT"})
    void manualRequeryIsGetOnlyOutsideTransactionsAndKeepsShipment(String outcome) {
        Fixture f=fixture();begin(f);var before=row(f);var unchanged=invariant(f);var transactionsBefore=transactionRows(f);
        when(gateway.retrievePaymentFlow(f.provider())).thenAnswer(call -> {
            boundary(f);
            if(outcome.equals("EXCEPTION") || outcome.equals("TIMEOUT"))throw new PaymentGatewayException("private",outcome.equals("TIMEOUT")?new java.net.SocketTimeoutException():new RuntimeException());
            return new PaymentFlowState(f.provider(),PaymentFlowStatus.valueOf(outcome),null,null);
        });
        if(outcome.equals("EXCEPTION") || outcome.equals("TIMEOUT"))assertThatThrownBy(()->requery.requery(f.order())).isInstanceOf(PaymentGatewayException.class);
        else assertThat(requery.requery(f.order())).isEqualTo(outcome.equals("SUCCEEDED")?AdminCaptureRequeryService.Result.CONFIRMED:AdminCaptureRequeryService.Result.WAITING);
        assertThat(invariant(f)).isEqualTo(unchanged);assertThat(historyCount(f)).isEqualTo(1);
        assertThat(row(f)).containsEntry("status","SHIPPED").containsEntry("shipped_at",before.get("shipped_at")).containsEntry("shipped_by_account_id",9L).containsEntry("shipment_capture_payment_id",f.payment());
        if(!outcome.equals("SUCCEEDED")){assertThat(row(f)).isEqualTo(before);assertThat(transactionRows(f)).isEqualTo(transactionsBefore);}
        else {var paid=row(f).get("paid_at");assertThat(paid).isNotNull();assertThat(requery.requery(f.order())).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);assertThat(row(f).get("paid_at")).isEqualTo(paid);}
        verify(gateway).retrievePaymentFlow(f.provider());verify(gateway,never()).capture(any());verifyNoMoreInteractions(gateway);
    }
    @ParameterizedTest @ValueSource(strings={"ordered","failed","fixed","foreign-fixed","multiple","provider","legacy"})
    void manualRequeryRejectsUnsafeTargetsWithoutGateway(String mode) {
        Fixture f=fixture();var initial=mode.equals("ordered") || mode.equals("legacy")?null:begin(f);
        if(mode.equals("provider")){assertThatThrownBy(()->jdbc.update("update payments set provider_payment_id=null where id=?",f.payment())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);verifyNoInteractions(gateway);return;}
        tx(() -> {
            if(mode.equals("failed"))results.apply(initial.snapshot(),new CaptureResult(CaptureResultStatus.FAILED,f.provider(),"failed","reason"));
            if(mode.equals("foreign-fixed")){var other=fixture();jdbc.update("update orders set shipment_capture_payment_id=? where id=?",other.payment(),f.order());}
            if(mode.equals("fixed"))jdbc.update("update orders set shipment_capture_payment_id=null where id=?",f.order());
            if(mode.equals("multiple") || mode.equals("legacy")){var t=new PaymentTransaction(payments.findById(f.payment()).orElseThrow(),PaymentTransactionType.CAPTURE,200,0,"extra-"+UUID.randomUUID(),now());transactions.saveAndFlush(t);}
            return null;
        });
        var before=row(f);var txBefore=transactionRows(f);var unchanged=invariant(f);
        assertThatThrownBy(()->requery.requery(f.order())).isInstanceOf(CaptureRequeryRejectedException.class);
        assertThat(row(f)).isEqualTo(before);assertThat(transactionRows(f)).isEqualTo(txBefore);assertThat(invariant(f)).isEqualTo(unchanged);verifyNoInteractions(gateway);
    }
    @ParameterizedTest @ValueSource(strings={"webhook","reconciliation","webhook-timeout"})
    void manualRequeryPrefersConcurrentCommittedSuccessToOldGetResult(String source) {
        Fixture f=fixture();var first=begin(f);var unchanged=invariant(f);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(gateway.retrievePaymentFlow(f.provider())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(calls.incrementAndGet()==1){
                if(source.equals("reconciliation"))assertThat(reconciliation.reconcile(first.transactionId())).isEqualTo(PaymentSynchronizationOutcome.APPLIED);
                else assertThat(webhook.synchronize(f.provider())).isEqualTo(PaymentSynchronizationOutcome.APPLIED);
                if(source.equals("webhook-timeout"))throw new PaymentGatewayException("timeout");
                return new PaymentFlowState(f.provider(),PaymentFlowStatus.PROCESSING,null,null);
            }
            return new PaymentFlowState(f.provider(),PaymentFlowStatus.SUCCEEDED,null,null);
        });
        authenticate(10,"second-admin");assertThat(requery.requery(f.order())).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);
        var paid=row(f).get("paid_at");assertThat(paid).isNotNull();assertThat(requery.requery(f.order())).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);
        assertThat(row(f)).containsEntry("paid_at",paid).containsEntry("shipped_by_account_id",9L);
        assertThat(transactionRows(f).getLast()).containsEntry("initiator_id",9L).containsEntry("internal_note","memo");
        assertThat(invariant(f)).isEqualTo(unchanged);assertThat(historyCount(f)).isEqualTo(1);verify(gateway,times(2)).retrievePaymentFlow(f.provider());verify(gateway,never()).capture(any());
    }
    @Test void twoManualQueriesBothGetButOnlyOneResultAndPaidAtAreApplied() throws Exception {
        Fixture f=fixture();begin(f);var both=new CountDownLatch(2);var unchanged=invariant(f);
        when(gateway.retrievePaymentFlow(f.provider())).thenAnswer(call -> {assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();both.countDown();await(both);return new PaymentFlowState(f.provider(),PaymentFlowStatus.SUCCEEDED,null,null);});
        var first=workers.submit(()->asAdmin(()->requery.requery(f.order())));var second=workers.submit(()->asAdmin(()->requery.requery(f.order())));
        assertThat(first.get(20,TimeUnit.SECONDS)).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);assertThat(second.get(20,TimeUnit.SECONDS)).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);
        var paid=row(f).get("paid_at");assertThat(paid).isNotNull();assertThat(requery.requery(f.order())).isEqualTo(AdminCaptureRequeryService.Result.CONFIRMED);assertThat(row(f).get("paid_at")).isEqualTo(paid);
        assertThat(transactionRows(f)).hasSize(2);assertThat(historyCount(f)).isEqualTo(1);assertThat(invariant(f)).isEqualTo(unchanged);verify(gateway,times(2)).retrievePaymentFlow(f.provider());verify(gateway,never()).capture(any());
    }
    @ParameterizedTest @ValueSource(strings={"prepare","latest"})
    void requeryRefreshesPreloadedOrderPaymentAndTransactionAfterOtherCommit(String phase) throws Exception {
        Fixture f=fixture();var first=begin(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var oldOrder=em.find(Order.class,f.order());var oldPayment=em.find(Payment.class,f.payment());var oldTransaction=em.find(PaymentTransaction.class,first.transactionId());
            assertThat(oldOrder.getPaidAt()).isNull();assertThat(oldPayment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);assertThat(oldTransaction.getStatus()).isEqualTo(PaymentTransactionStatus.PENDING);
            try{assertThat(workers.submit(()->results.apply(first.snapshot(),captured(f))).get(20,TimeUnit.SECONDS)).isEqualTo(PaymentResultApplication.APPLIED);}catch(Exception e){throw new RuntimeException(e);}
            var disposition=phase.equals("prepare")?requeryPreparation.prepare(f.order()).disposition():requeryPreparation.latest(first.snapshot());
            assertThat(disposition).isEqualTo(PaymentStartDisposition.ALREADY_APPLIED);assertThat(em.find(Order.class,f.order())).isSameAs(oldOrder);assertThat(oldOrder.getPaidAt()).isNotNull();assertThat(oldPayment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);assertThat(oldTransaction.getStatus()).isEqualTo(PaymentTransactionStatus.SUCCEEDED);
        });
        assertThat(historyCount(f)).isEqualTo(1);verifyNoInteractions(gateway);
    }
    @ParameterizedTest @ValueSource(strings={"key","amount","revision","provider"})
    void originalSnapshotIdentityIsRecheckedAfterGateway(String mismatch) {
        Fixture f=fixture();var first=begin(f);var unchanged=invariant(f);
        when(gateway.retrievePaymentFlow(f.provider())).thenAnswer(call -> {
            tx(()->{orders.findByIdForUpdate(f.order()).orElseThrow();payments.findByIdForUpdate(f.payment()).orElseThrow();
                switch(mismatch){case "key"->jdbc.update("update payment_transactions set idempotency_key=? where id=?","changed-"+UUID.randomUUID(),first.transactionId());case "amount"->jdbc.update("update payment_transactions set amount=300 where id=?",first.transactionId());case "revision"->jdbc.update("update payment_transactions set order_content_revision=1 where id=?",first.transactionId());case "provider"->jdbc.update("update payments set provider_payment_id=? where id=?","changed-"+UUID.randomUUID(),f.payment());}return null;});
            return new PaymentFlowState(f.provider(),PaymentFlowStatus.SUCCEEDED,null,null);
        });
        assertThatThrownBy(()->requery.requery(f.order())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);assertThat(row(f)).containsEntry("status","SHIPPED").containsEntry("paid_at",null);assertThat(historyCount(f)).isEqualTo(1);assertThat(invariant(f)).isEqualTo(unchanged);verify(gateway,never()).capture(any());
    }
    @Test void requeryWaitsOnActualOrderLockThenRejectsExpiredAuthorization() throws Exception {
        Fixture f=fixture();begin(f);var ready=new CompletableFuture<Integer>();
        var blocker=workers.submit(()->tx(()->{orders.findByIdForUpdate(f.order()).orElseThrow();payments.findByIdForUpdate(f.payment()).orElseThrow();jdbc.update("update payments set status='CANCELLED' where id=?",f.payment());ready.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));await(release);return null;}));
        int pid=ready.get(20,TimeUnit.SECONDS);var waiting=workers.submit(()->asAdmin(()->requery.requery(f.order())));blockedBy(pid);release.countDown();blocker.get(20,TimeUnit.SECONDS);
        assertThatThrownBy(()->waiting.get(20,TimeUnit.SECONDS)).hasCauseInstanceOf(CaptureRequeryRejectedException.class);verifyNoInteractions(gateway);assertThat(historyCount(f)).isEqualTo(1);assertThat(row(f)).containsEntry("status","SHIPPED").containsEntry("paid_at",null);
    }
    @ParameterizedTest @ValueSource(strings={"dirty","order-readonly","read-only","repeatable"})
    void requeryPreparationPreservesExistingTransactionSafety(String mode) {
        Fixture f=fixture();begin(f);var before=row(f);var template=new TransactionTemplate(manager);
        if(mode.equals("read-only"))template.setReadOnly(true);if(mode.equals("repeatable"))template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.executeWithoutResult(status->{var entity=em.find(Order.class,f.order());if(mode.equals("dirty"))entity.setTotalAmount(300);if(mode.equals("order-readonly"))em.unwrap(Session.class).setReadOnly(entity,true);
            assertThatThrownBy(()->requeryPreparation.prepare(f.order())).isInstanceOf(IllegalStateException.class);assertThat(status.isRollbackOnly()).isTrue();if(mode.equals("dirty"))assertThat(entity.getTotalAmount()).isEqualTo(300);status.setRollbackOnly();});
        assertThat(row(f)).isEqualTo(before);verifyNoInteractions(gateway);
    }
    @Test void manualRequeryRejectsAnOuterTransactionBeforeGateway() {
        Fixture f=fixture();begin(f);tx(()->{assertThatThrownBy(()->requery.requery(f.order())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);return null;});verifyNoInteractions(gateway);
    }
    @ParameterizedTest @ValueSource(strings={"insert","flush","commit"})
    void immutableRequestFailureRollsBackEveryStartWrite(String point) {
        Fixture f=fixture();var before=row(f);var txBefore=transactionRows(f);
        if(point.equals("insert"))tx(()->{doThrow(new IllegalStateException("insert failed")).when(requestStore).insert(any(),any(),any(),any(),any());return null;});
        if(point.equals("flush"))doThrow(new IllegalStateException("flush failed")).when(transactions).saveAndFlush(argThat(t->t.getTransactionType()==PaymentTransactionType.CAPTURE));
        assertThatThrownBy(()->tx(()->{var value=begin(f);if(point.equals("commit"))TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("commit failed");}});return value;})).isInstanceOfAny(IllegalStateException.class,org.springframework.dao.InvalidDataAccessApiUsageException.class);
        assertThat(row(f)).isEqualTo(before);assertThat(transactionRows(f)).isEqualTo(txBefore);assertThat(historyCount(f)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from capture_requests where payment_id=?",Integer.class,f.payment())).isZero();verifyNoInteractions(gateway);
    }
    @Test void missingConnectionRejectsNewBeforeCreatingCaptureButDoesNotAffectExistingContinuation() {
        Fixture fresh=fixture();Fixture pending=fixture();begin(pending);
        doThrow(new IllegalStateException("not configured")).when(connection).requireIdentity(any());
        assertThatThrownBy(()->begin(fresh)).isInstanceOf(IllegalStateException.class);
        assertThat(row(fresh)).containsEntry("status","ORDERED");assertThat(transactionRows(fresh)).hasSize(1);
        when(gateway.retrievePaymentFlow(pending.provider())).thenReturn(new PaymentFlowState(pending.provider(),PaymentFlowStatus.PROCESSING,null,null));
        assertThat(capture.captureForShipment(pending.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.PENDING);
        verify(gateway,never()).capture(any());
    }
    @ParameterizedTest @ValueSource(strings={"webhook","reconciliation"})
    void committedSuccessBeforeInitialPreparationSuppressesPost(String source) {
        Fixture f=fixture();var first=begin(f);
        when(gateway.retrievePaymentFlow(f.provider())).thenReturn(new PaymentFlowState(f.provider(),PaymentFlowStatus.SUCCEEDED,null,null));
        if(source.equals("webhook"))webhook.synchronize(f.provider());else reconciliation.reconcile(first.transactionId());
        assertThat(initialPreparation.prepare(first.initialPost())).isInstanceOf(CaptureInitialPostPreparationService.AlreadyApplied.class);
        verify(gateway,never()).capture(any());assertThat(historyCount(f)).isEqualTo(1);
    }
    @Test void initialCapabilityCannotBeConsumedTwiceOrRecreatedByRepost() {
        Fixture f=fixture();var first=begin(f);
        assertThat(initialPreparation.prepare(first.initialPost())).isInstanceOf(CaptureInitialPostPreparationService.Ready.class);
        assertThatThrownBy(()->initialPreparation.prepare(first.initialPost())).isInstanceOf(IllegalStateException.class);
        assertThat(begin(f).initialPost()).isNull();verifyNoInteractions(gateway);
    }
    @ParameterizedTest @ValueSource(strings={"missing","wrong-id","flow","amount","revision","key","connection","shortened"})
    void initialPreparationRejectsMissingOrMismatchedStoredRequest(String field) {
        Fixture f=fixture();var first=begin(f);
        var stored=tx(()->requestStore.findByIdAndCaptureTransactionId(first.initialPost().requestId(),first.transactionId()).orElseThrow());
        if(field.equals("missing"))tx(()->{doReturn(Optional.empty()).when(requestStore).findByIdAndCaptureTransactionId(anyLong(),anyLong());return null;});
        else if(field.equals("shortened"))doReturn(java.time.Duration.ofNanos(1)).when(safety).postMaxAge();
        else {
            var http=stored.httpRequest();var identity=http.connection();
            if(field.equals("connection"))identity=new com.example.ecsite.payment.capture.CaptureConnectionIdentity("other","test-account","TEST","https://api.pay.jp");
            var other=com.example.ecsite.payment.capture.CaptureHttpRequest.create(identity,field.equals("flow")?"pfw_other":http.flowId(),field.equals("amount")?201:http.amount(),field.equals("key")?"other":http.idempotencyKey());
            var changed=new com.example.ecsite.payment.capture.StoredCaptureRequest(field.equals("wrong-id")?stored.id()+1:stored.id(),stored.paymentId(),stored.captureTransactionId(),field.equals("revision")?1:stored.orderContentRevision(),other,stored.createdAt(),stored.replayNotAfter());
            tx(()->{doReturn(Optional.of(changed)).when(requestStore).findByIdAndCaptureTransactionId(anyLong(),anyLong());return null;});
        }
        assertThatThrownBy(()->initialPreparation.prepare(first.initialPost())).isInstanceOfAny(IllegalStateException.class,IllegalArgumentException.class);
        assertThat(row(f)).containsEntry("status","SHIPPED").containsEntry("paid_at",null);assertThat(historyCount(f)).isEqualTo(1);verifyNoInteractions(gateway);
    }
    @Test void extendingCurrentConfigurationNeverExtendsTheStoredDeadline() {
        doReturn(java.time.Duration.ofHours(1)).when(safety).postMaxAge();Fixture f=fixture();var first=begin(f);
        doReturn(java.time.Duration.ofHours(23)).when(safety).postMaxAge();
        var ready=(CaptureInitialPostPreparationService.Ready)initialPreparation.prepare(first.initialPost());
        assertThat(java.time.Duration.between(ready.createdAt(),ready.notAfter())).isEqualTo(java.time.Duration.ofHours(1));
        assertThatThrownBy(()->ready.requireWithinWindow(ready.notAfter())).isInstanceOf(IllegalStateException.class);
        ready.requireWithinWindow(ready.notAfter().minusNanos(1));
    }
    @ParameterizedTest @ValueSource(strings={"dirty","order","payment","transaction","read-only","repeatable"})
    void initialPreparationRetainsWriteContextSafety(String mode) {
        Fixture f=fixture();var first=begin(f);var template=new TransactionTemplate(manager);
        if(mode.equals("read-only"))template.setReadOnly(true);if(mode.equals("repeatable"))template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.executeWithoutResult(status->{
            switch(mode){case "dirty"->em.find(Order.class,f.order()).setTotalAmount(999);case "order"->em.unwrap(Session.class).setReadOnly(em.find(Order.class,f.order()),true);case "payment"->em.unwrap(Session.class).setReadOnly(em.find(Payment.class,f.payment()),true);case "transaction"->em.unwrap(Session.class).setReadOnly(em.find(PaymentTransaction.class,first.transactionId()),true);}
            assertThatThrownBy(()->initialPreparation.prepare(first.initialPost())).isInstanceOf(IllegalStateException.class);assertThat(status.isRollbackOnly()).isTrue();status.setRollbackOnly();
        });verifyNoInteractions(gateway);assertThat(row(f)).containsEntry("status","SHIPPED").containsEntry("paid_at",null);
    }
    @Test void shipmentRequestAndOperationAreInvisibleBeforeTheSameCommit() throws Exception {
        Fixture f=fixture();var entered=new CountDownLatch(1);
        var creating=workers.submit(()->asAdmin(()->tx(()->{var value=begin(f);entered.countDown();await(release);return value;})));
        await(entered);
        assertThat(row(f)).containsEntry("status","ORDERED");assertThat(historyCount(f)).isZero();assertThat(transactionRows(f)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from capture_requests where payment_id=?",Integer.class,f.payment())).isZero();
        release.countDown();creating.get(20,TimeUnit.SECONDS);boundary(f);verifyNoInteractions(gateway);
    }
    @Test void initialPreparationRefreshesPreloadedEntitiesAfterAnotherResultCommits() throws Exception {
        Fixture f=fixture();var first=begin(f);
        tx(()->{var old=em.find(Order.class,f.order());var p=em.find(Payment.class,f.payment());var t=em.find(PaymentTransaction.class,first.transactionId());
            try{assertThat(workers.submit(()->results.apply(first.snapshot(),captured(f))).get(20,TimeUnit.SECONDS)).isEqualTo(PaymentResultApplication.APPLIED);}catch(Exception e){throw new RuntimeException(e);}
            assertThat(initialPreparation.prepare(first.initialPost())).isInstanceOf(CaptureInitialPostPreparationService.AlreadyApplied.class);
            assertThat(em.find(Order.class,f.order())).isSameAs(old);assertThat(old.getPaidAt()).isNotNull();assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);assertThat(t.getStatus()).isEqualTo(PaymentTransactionStatus.SUCCEEDED);return null;
        });verifyNoInteractions(gateway);assertThat(historyCount(f)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"actor","note","duplicate"})
    void initialPreparationRejectsInconsistentShipmentHistory(String field) {
        Fixture f=fixture();var first=begin(f);
        if(field.equals("duplicate"))tx(()->{histories.record(orders.findByIdForUpdate(f.order()).orElseThrow(),OrderStatus.ORDERED,OrderStatus.SHIPPED,OrderStatusHistoryActorType.ADMIN,9L,"shipper","memo");return null;});
        else jdbc.update(field.equals("actor")?"update order_status_histories set changed_by_username='other' where order_id=?":"update order_status_histories set internal_note='other' where order_id=?",f.order());
        assertThatThrownBy(()->initialPreparation.prepare(first.initialPost())).isInstanceOf(IllegalStateException.class);verifyNoInteractions(gateway);
        assertThat(row(f)).containsEntry("status","SHIPPED").containsEntry("paid_at",null);
    }
    @Test void shippedPendingWithoutStoredRequestOrNewSettingsRemainsGetOnly() {
        Fixture f=fixture();tx(()->{var order=orders.findByIdForUpdate(f.order()).orElseThrow();var p=payments.findByOrderIdForUpdate(f.order()).getFirst();var now=now();
            transactions.saveAndFlush(new PaymentTransaction(p,PaymentTransactionType.CAPTURE,200,0,UUID.randomUUID().toString(),now,new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,9L,"shipper","memo")));
            order.confirmCardShipment(now,9L,"shipper",p.getId());histories.record(order,OrderStatus.ORDERED,OrderStatus.SHIPPED,OrderStatusHistoryActorType.ADMIN,9L,"shipper","memo");return null;
        });
        doThrow(new IllegalStateException("new connection absent")).when(connection).requireIdentity(any());
        assertThat(jdbc.queryForObject("select count(*) from capture_requests where payment_id=?",Integer.class,f.payment())).isZero();
        when(gateway.retrievePaymentFlow(f.provider())).thenReturn(new PaymentFlowState(f.provider(),PaymentFlowStatus.SUCCEEDED,null,null));
        assertThat(capture.captureForShipment(f.order(),9L,"shipper",null).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        assertThat(row(f)).containsEntry("status","SHIPPED");assertThat(row(f).get("paid_at")).isNotNull();assertThat(historyCount(f)).isEqualTo(1);verify(gateway,never()).capture(any());
    }
    @Test void deferredDatabaseCommitFailureRollsBackAllStartRecords() {
        Fixture f=fixture();var before=row(f);var txBefore=transactionRows(f);
        String name="b2_commit_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$ begin if NEW.id="+f.order()+" and NEW.status='SHIPPED' then raise exception 'injected deferred commit failure' using errcode='23514'; end if; return NEW; end $$");
        try {
            jdbc.execute("create constraint trigger "+name+" after update on orders deferrable initially deferred for each row execute function "+name+"()");
            assertThatThrownBy(()->begin(f)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasStackTraceContaining("injected deferred commit failure");
            assertThat(row(f)).isEqualTo(before);assertThat(transactionRows(f)).isEqualTo(txBefore);assertThat(historyCount(f)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from capture_requests where payment_id=?",Integer.class,f.payment())).isZero();verifyNoInteractions(gateway);
        } finally {jdbc.execute("drop trigger if exists "+name+" on orders");jdbc.execute("drop function "+name+"()");}
    }
    void await(CountDownLatch latch) {try{assertThat(latch.await(20,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    void blockedBy(int pid) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){if(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid))",Integer.class,pid)>0)return;new CountDownLatch(1).await(20,TimeUnit.MILLISECONDS);}throw new AssertionError("No actual PostgreSQL lock wait");}
    record Fixture(Long order,Long payment,Long product,String provider) {}
}
