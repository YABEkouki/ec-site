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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderPaymentGuardPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature120_order_guard").withUsername("feature120").withPassword("feature120-test")
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
    @MockitoBean PaymentGateway gateway;
    @MockitoBean OrderAmountCalculator calculator;
    ExecutorService workers;
    CountDownLatch entered, release;
    @BeforeEach void setup() {
        workers = Executors.newFixedThreadPool(2); entered = new CountDownLatch(1); release = new CountDownLatch(1);
    }
    @AfterEach void cleanup() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
    @ParameterizedTest @EnumSource(value = PaymentTransactionType.class, names = {"AUTHORIZE", "CAPTURE", "CANCEL"})
    void pendingOperationRejectsItemChangeWithoutAnySideEffect(PaymentTransactionType type) {
        Fixture f = pending(type); Map<String,Object> before = state(f);
        assertThatThrownBy(() -> change(f)).isInstanceOf(InvalidOrderStatusException.class);
        assertThat(state(f)).isEqualTo(before); verifyNoInteractions(calculator, gateway);
    }
    @ParameterizedTest @EnumSource(value = PaymentTransactionType.class, names = {"AUTHORIZE", "CAPTURE", "CANCEL"})
    void pendingOperationRejectsManualPayment(PaymentTransactionType type) {
        Fixture f = pending(type); Map<String,Object> before = state(f);
        assertThatThrownBy(() -> orderService.markAsPaid(f.orderId, 1L, "admin", null)).isInstanceOf(RuntimeException.class);
        assertThat(state(f)).isEqualTo(before);
    }
    @ParameterizedTest @EnumSource(value = PaymentTransactionType.class, names = {"AUTHORIZE", "CAPTURE", "CANCEL"})
    void pendingOperationRejectsManualShipping(PaymentTransactionType type) {
        Fixture f = pending(type);
        tx(() -> { orders.findByIdForUpdate(f.orderId).orElseThrow().markAsPaid(); return null; });
        prepareManualShipment(f);
        authenticateShipmentAdmin();
        Map<String,Object> before = state(f);
        assertThatThrownBy(() -> orderService.markAsShipped(f.orderId, 1L, "admin", null))
                .isInstanceOf(InvalidOrderStatusException.class).hasMessageContaining("決済処理中");
        assertThat(state(f)).isEqualTo(before);
    }
    @ParameterizedTest @EnumSource(value = PaymentTransactionType.class, names = {"AUTHORIZE", "CAPTURE", "CANCEL"})
    void pendingOperationRejectsDbOnlyCancellation(PaymentTransactionType type) {
        Fixture f = pending(type); Map<String,Object> before = state(f);
        assertThatThrownBy(() -> orderService.cancelOrder(f.orderId, 1L, "admin", null)).isInstanceOf(RuntimeException.class);
        assertThat(state(f)).isEqualTo(before);
    }
    @Test void normalDecreaseAndRemovalRestoreStockAndAllowDecreasedCapture() {
        Fixture f = fixture(true); price(f, 1000);
        assertThat(change(f)).isTrue();
        assertThat(jdbc.queryForMap("select total_amount, content_revision from orders where id=?", f.orderId))
                .containsEntry("total_amount",1000).containsEntry("content_revision",1);
        assertThat(jdbc.queryForList("select quantity from order_items where order_id=?", Integer.class, f.orderId)).containsExactly(1);
        assertThat(stock(f)).containsExactly(11,12);
        assertThat(number("order_content_change_histories", f)).isEqualTo(1);
        assertThat(number("stock_movements", f)).isEqualTo(2);
        PaymentCaptureStart start = preparedCapture(f);
        assertThat(start.amount()).isEqualTo(1000);
        assertThat(jdbc.queryForObject("select order_content_revision from payment_transactions where id=?", Integer.class,start.transactionId())).isEqualTo(1);
    }
    @Test void localAdminCancellationRestoresStockOnceWithoutGateway() {
        Fixture f = fixture(false);
        assertThat(adminCancel.cancel(f.orderId,1L,"admin","local").status()).isEqualTo(CancellationResultStatus.CANCELLED);
        assertThat(stock(f)).containsExactly(12,12);
        assertThat(number("order_status_histories",f)).isEqualTo(1);
        assertThat(number("stock_movements",f)).isEqualTo(2);
        assertThatThrownBy(() -> adminCancel.cancel(f.orderId,1L,"admin","again")).isInstanceOf(RuntimeException.class);
        assertThat(number("order_status_histories",f)).isEqualTo(1); verifyNoInteractions(gateway);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void customerAndAdminCancellationKeepOriginalActorAndRestoreOnce(boolean adminFirst) throws Exception {
        Fixture f = fixture(true);
        when(gateway.cancelAuthorization(any())).thenAnswer(call -> {
            assertApiBoundary(f); entered.countDown(); await(release);
            return new CancellationResult(CancellationResultStatus.CANCELLED,f.providerId);
        });
        when(gateway.retrievePaymentFlow(f.providerId)).thenAnswer(call -> {
            assertApiBoundary(f); return new PaymentFlowState(f.providerId,PaymentFlowStatus.CANCELED,null,null);
        });
        Future<?> first = workers.submit(() -> {
            if(adminFirst) adminCancel.cancel(f.orderId,1L,"original-admin","original-note");
            else userCancel.cancelForUser(f.orderId,f.userId,"original-user");
        });
        await(entered);
        CancellationResult result = adminFirst ? userCancel.cancelForUser(f.orderId,f.userId,"other-user")
                : adminCancel.cancel(f.orderId,1L,"other-admin","other-note");
        assertThat(result.status()).isEqualTo(CancellationResultStatus.CANCELLED);
        release.countDown(); first.get(20,TimeUnit.SECONDS);
        assertThat(jdbc.queryForMap("select changed_by_type, changed_by_account_id, changed_by_username, internal_note from order_status_histories where order_id=?",f.orderId))
                .containsEntry("changed_by_type",adminFirst?"ADMIN":"USER")
                .containsEntry("changed_by_account_id",adminFirst?1L:f.userId)
                .containsEntry("changed_by_username",adminFirst?"original-admin":"original-user")
                .containsEntry("internal_note",adminFirst?"original-note":null);
        assertThat(stock(f)).containsExactly(12,12); assertThat(number("stock_movements",f)).isEqualTo(2);
        verify(gateway).cancelAuthorization(any()); verify(gateway).retrievePaymentFlow(f.providerId);
    }
    @Test void paymentStartCommittedWhileChangeWaitsMakesChangeReject() throws Exception {
        Fixture f = fixture(true); CompletableFuture<Integer> blocker = new CompletableFuture<>();
        Future<?> first = workers.submit(() -> tx(() -> {
            orders.findByIdForUpdate(f.orderId).orElseThrow();
            pendingCaptureUnderOrderLock(f);
            blocker.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
            awaitUnchecked(release); return null;
        }));
        int pid = blocker.get(20,TimeUnit.SECONDS);
        Future<?> changing = workers.submit(() -> change(f));
        assertWait(pid); Map<String,Object> before = state(f);
        release.countDown(); first.get(20,TimeUnit.SECONDS);
        assertThatThrownBy(() -> changing.get(20,TimeUnit.SECONDS)).hasCauseInstanceOf(InvalidOrderStatusException.class);
        assertThat(state(f)).isEqualTo(before); verifyNoInteractions(calculator,gateway);
    }
    @Test void itemChangeCommitsBeforePreparationAndCaptureUsesNewSnapshot() throws Exception {
        Fixture f = fixture(true); price(f,1000); CompletableFuture<Integer> blocker = new CompletableFuture<>();
        when(calculator.calculate(any(),any(ChargeTaxSnapshot.class))).thenAnswer(call -> {
            blocker.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
            entered.countDown(); await(release); return amount(f,1000);
        });
        Future<?> changing = workers.submit(() -> change(f)); await(entered);
        Future<PaymentCaptureStart> starting = workers.submit(() -> preparedCapture(f));
        assertWait(blocker.get(20,TimeUnit.SECONDS)); release.countDown(); changing.get(20,TimeUnit.SECONDS);
        PaymentCaptureStart result = starting.get(20,TimeUnit.SECONDS);
        assertThat(result.amount()).isEqualTo(1000);
        assertThat(jdbc.queryForObject("select order_content_revision from payment_transactions where id=?",Integer.class,result.transactionId())).isEqualTo(1);
        assertThat(stock(f)).containsExactly(11,12);
    }
    @Test void adminCancellationRechecksPaymentCreatedWhileWaitingForOrder() throws Exception {
        Fixture f = fixture(false); CompletableFuture<Integer> blocker = new CompletableFuture<>();
        Future<?> creating = workers.submit(() -> tx(() -> {
            Order o=orders.findByIdForUpdate(f.orderId).orElseThrow(); createAuthorized(o,f.providerId);
            blocker.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); awaitUnchecked(release); return null;
        }));
        int pid=blocker.get(20,TimeUnit.SECONDS);
        when(gateway.cancelAuthorization(any())).thenAnswer(call -> { assertApiBoundary(f); return new CancellationResult(CancellationResultStatus.CANCELLED,f.providerId); });
        Future<?> cancelling = workers.submit(() -> adminCancel.cancel(f.orderId,1L,"admin",null));
        assertWait(pid); release.countDown(); creating.get(20,TimeUnit.SECONDS); cancelling.get(20,TimeUnit.SECONDS);
        verify(gateway).cancelAuthorization(any()); assertThat(number("order_status_histories",f)).isEqualTo(1);
        assertThat(stock(f)).containsExactly(12,12);
    }
    @Test void expiredModificationPeriodStillRejectsWithoutMutation() {
        Fixture f=fixture(true);
        tx(() -> { jdbc.update("update orders set change_deadline_at=? where id=?",LocalDateTime.now().minusDays(1),f.orderId);return null; });
        Map<String,Object> before=state(f);
        assertThatThrownBy(() -> change(f)).isInstanceOf(InvalidOrderStatusException.class);
        assertThat(state(f)).isEqualTo(before);verifyNoInteractions(calculator,gateway);
    }

    @Test void normalManualPaymentAndShippingRemainAvailableWithoutExternalPayment() {
        Fixture f=fixture(false);
        orderService.markAsPaid(f.orderId,1L,"admin",null);
        prepareManualShipment(f);
        authenticateShipmentAdmin();
        orderService.markAsShipped(f.orderId,1L,"admin",null);
        assertThat(jdbc.queryForObject("select status from orders where id=?",String.class,f.orderId)).isEqualTo("SHIPPED");
        assertThat(number("order_status_histories",f)).isEqualTo(2);assertThat(stock(f)).containsExactly(10,10);
        verifyNoInteractions(gateway);
    }

    @Test void capturePendingRejectsAdminCancellationWithoutChangingAnything() {
        Fixture f=pending(PaymentTransactionType.CAPTURE);Map<String,Object> before=state(f);
        assertThatThrownBy(() -> adminCancel.cancel(f.orderId,1L,"admin",null)).isInstanceOf(RuntimeException.class);
        assertThat(state(f)).isEqualTo(before);verifyNoInteractions(gateway);
    }

    private Fixture pending(PaymentTransactionType type) {
        Fixture f=fixture(type!=PaymentTransactionType.AUTHORIZE);
        switch(type) {
            case AUTHORIZE -> authorizationStart.start(f.orderId);
            // Keep an ORDERED + PENDING legacy fixture: guards must still reject its mutations.
            case CAPTURE -> tx(() -> { pendingCaptureUnderOrderLock(f); return null; });
            case CANCEL -> userCancelStart.start(f.orderId,f.userId,"user");
            default -> throw new AssertionError(type);
        }
        return f;
    }
    private PaymentCaptureStart preparedCapture(Fixture f) {
        // Preparation follows any completed item change, then the capture service starts a clean TX.
        prepareManualShipment(f);authenticateShipmentAdmin();
        try{return captureStart.start(f.orderId,1L,"admin",null);}
        finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    private void pendingCaptureUnderOrderLock(Fixture f) {
        orders.findByIdForUpdate(f.orderId).orElseThrow();
        Payment payment=payments.findByOrderIdForUpdate(f.orderId).getFirst();
        transactions.saveAndFlush(new PaymentTransaction(payment,PaymentTransactionType.CAPTURE,
                payment.getAmount(),0,UUID.randomUUID().toString(),orders.findCurrentTimeInTokyo(),
                new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN,1L,"admin",null)));
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
    private void assertApiBoundary(Fixture f) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
        tx(() -> { jdbc.execute("set local lock_timeout='2s'");orders.findByIdForUpdate(f.orderId).orElseThrow();return null; });
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? and t.transaction_type='CANCEL'",Integer.class,f.orderId)).isEqualTo(1);
    }
    private void assertWait(int blocker) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            var pids=jdbc.queryForList("select pid from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid))",Integer.class,blocker);
            if(!pids.isEmpty()) { assertThat(pids.getFirst()).isNotEqualTo(blocker);return; }
            new CountDownLatch(1).await(25,TimeUnit.MILLISECONDS);
        }
        fail("Expected independent DB connection waiting for Order lock");
    }
    private void authenticateShipmentAdmin() {
        var actor=new com.example.ecsite.security.AdminUserDetails(1L,"admin","password",true,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,"password",actor.getAuthorities()));
    }
    private void prepareManualShipment(Fixture f) {
        tx(() -> {
            Order order=orders.findByIdForUpdate(f.orderId).orElseThrow();
            var now=LocalDateTime.now(); order.setOrderedAt(now.minusDays(2));
            org.springframework.test.util.ReflectionTestUtils.setField(order,"changeDeadlineAt",now.minusDays(1));
            order.beginShippingPreparation(now.minusHours(1),7L,"preparer");
            return null;
        });
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
