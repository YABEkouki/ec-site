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
import com.example.ecsite.form.OrderShippingAddressForm;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderModificationGuardPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature120_modification_guard").withUsername("feature120").withPassword("feature120-test")
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
    @MockitoSpyBean OrderRepository orders;
    @Autowired PaymentCancellationResultService cancellationResult;
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
        release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
    enum Operation { ADDRESS, ADDRESS_NO_OP, ITEMS, LOCAL_USER_CANCEL, LOCAL_ADMIN_CANCEL, CARD_USER_CANCEL, CARD_ADMIN_CANCEL }
    @ParameterizedTest @EnumSource(Operation.class)
    void expiredDeadlineRejectsEveryNewModificationWithoutSideEffects(Operation operation) {
        assertRejected(operation, false);
    }
    @ParameterizedTest @EnumSource(Operation.class)
    void preparationBeforeDeadlineRejectsEveryNewModificationWithoutSideEffects(Operation operation) {
        assertRejected(operation, true);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints = {-1,0,1})
    void databaseTimeBoundaryIsStrictEvenWhenApplicationClockDiffers(int offsetMicros) {
        Fixture f=fixture(false);
        LocalDateTime deadline=LocalDateTime.of(2030,1,1,12,0);
        jdbc.update("update orders set change_deadline_at=? where id=?",deadline,f.orderId);
        // Only the database clock query is controlled; locks and mutations use real PostgreSQL.
        doReturn(deadline.plusNanos(offsetMicros*1000L)).when(orders).findCurrentTimeInTokyo();
        if(offsetMicros<0) {
            assertThat(orderService.changeShippingAddressForUser(f.orderId,f.userId,"user",address("Changed"))).isTrue();
            assertThat(number("order_shipping_address_histories",f)).isEqualTo(1);
        } else {
            Map<String,Object> before=state(f);
            assertThatThrownBy(() -> perform(Operation.ADDRESS,f)).isInstanceOf(InvalidOrderStatusException.class);
            assertThat(state(f)).isEqualTo(before);
        }
        verifyNoInteractions(gateway,calculator);
    }
    private void assertRejected(Operation operation, boolean prepared) {
        Fixture f=fixture(operation==Operation.CARD_USER_CANCEL || operation==Operation.CARD_ADMIN_CANCEL);
        if(prepared) prepare(f);
        else jdbc.update("update orders set ordered_at=timezone('Asia/Tokyo',clock_timestamp())-interval '2 days', change_deadline_at=timezone('Asia/Tokyo',clock_timestamp())-interval '1 day' where id=?",f.orderId);
        Map<String,Object> before=state(f);
        assertThatThrownBy(() -> perform(operation,f)).isInstanceOf(InvalidOrderStatusException.class);
        assertThat(state(f)).isEqualTo(before);
        verifyNoInteractions(gateway,calculator);
    }
    private void perform(Operation operation,Fixture f) {
        switch(operation) {
            case ADDRESS, ADDRESS_NO_OP -> orderService.changeShippingAddressForUser(f.orderId,f.userId,"user",address(operation==Operation.ADDRESS?"Changed":"Original"));
            case ITEMS -> change(f);
            case LOCAL_USER_CANCEL -> orderService.cancelOrderForUser(f.orderId,f.userId,"user");
            case LOCAL_ADMIN_CANCEL -> orderService.cancelOrder(f.orderId,1L,"admin",null);
            case CARD_USER_CANCEL -> userCancel.cancelForUser(f.orderId,f.userId,"user");
            case CARD_ADMIN_CANCEL -> adminCancel.cancel(f.orderId,1L,"admin",null);
        }
    }
    @Test void acceptedCancellationResultAfterExpiryIsAppliedOnce() {
        Fixture f=fixture(true);
        PaymentCancellationStart start=userCancelStart.start(f.orderId,f.userId,"original-user");
        jdbc.update("update orders set ordered_at=timezone('Asia/Tokyo',clock_timestamp())-interval '2 days', change_deadline_at=timezone('Asia/Tokyo',clock_timestamp())-interval '1 day' where id=?",f.orderId);
        CancellationResult result=new CancellationResult(CancellationResultStatus.CANCELLED,f.providerId);
        assertThat(cancellationResult.apply(start.snapshot(),result)).isEqualTo(PaymentResultApplication.APPLIED);
        Map<String,Object> after=state(f);
        cancellationResult.apply(start.snapshot(),result);
        assertThat(state(f)).isEqualTo(after);
        assertRestoredOnce(f,"USER","original-user");
        verifyNoInteractions(gateway);
    }
    @Test void httpRetryAfterExpiryUsesGetAndOriginalAdminActor() {
        Fixture f=fixture(true);
        when(gateway.cancelAuthorization(any())).thenReturn(new CancellationResult(CancellationResultStatus.PENDING,f.providerId));
        assertThat(adminCancel.cancel(f.orderId,1L,"original-admin","original-note").status()).isEqualTo(CancellationResultStatus.PENDING);
        jdbc.update("update orders set ordered_at=timezone('Asia/Tokyo',clock_timestamp())-interval '2 days', change_deadline_at=timezone('Asia/Tokyo',clock_timestamp())-interval '1 day' where id=?",f.orderId);
        when(gateway.retrievePaymentFlow(f.providerId)).thenReturn(new PaymentFlowState(f.providerId,PaymentFlowStatus.CANCELED,null,null));
        assertThat(userCancel.cancelForUser(f.orderId,f.userId,"retry-user").status()).isEqualTo(CancellationResultStatus.CANCELLED);
        assertRestoredOnce(f,"ADMIN","original-admin");
        assertThat(jdbc.queryForObject("select internal_note from order_status_histories where order_id=?",String.class,f.orderId)).isEqualTo("original-note");
        verify(gateway,times(1)).cancelAuthorization(any());
        verify(gateway,times(1)).retrievePaymentFlow(f.providerId);
    }
    private void assertRestoredOnce(Fixture f,String actor,String username) {
        assertThat(stock(f)).containsExactly(12,12);
        assertThat(number("stock_movements",f)).isEqualTo(2);
        assertThat(number("order_status_histories",f)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select changed_by_type,changed_by_username from order_status_histories where order_id=?",f.orderId))
                .containsEntry("changed_by_type",actor).containsEntry("changed_by_username",username);
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? and t.transaction_type='CANCEL'",Integer.class,f.orderId)).isEqualTo(1);
    }
    @Test void preparationCommittedWhileAddressChangeWaitsIsRechecked() throws Exception {
        Fixture f=fixture(false);CompletableFuture<Integer> blocker=new CompletableFuture<>();
        Future<?> preparing=workers.submit(() -> tx(() -> {
            orders.findByIdForUpdate(f.orderId).orElseThrow();prepare(f);
            blocker.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
            awaitUnchecked(release);return null;
        }));
        int pid=blocker.get(20,TimeUnit.SECONDS);
        Future<?> changing=workers.submit(() -> perform(Operation.ADDRESS,f));
        assertWait(pid);release.countDown();preparing.get(20,TimeUnit.SECONDS);
        assertThatThrownBy(() -> changing.get(20,TimeUnit.SECONDS)).hasCauseInstanceOf(InvalidOrderStatusException.class);
        assertThat(jdbc.queryForObject("select shipping_name from orders where id=?",String.class,f.orderId)).isEqualTo("Original");
        assertThat(number("order_shipping_address_histories",f)).isZero();
        assertThat(stock(f)).containsExactly(10,10);verifyNoInteractions(gateway,calculator);
    }
    @Test void deadlineIsRecheckedAfterWaitingForPaymentLock() throws Exception {
        Fixture f=fixture(true);
        LocalDateTime deadline=jdbc.queryForObject("select change_deadline_at from orders where id=?",LocalDateTime.class,f.orderId);
        var databaseNow=new java.util.concurrent.atomic.AtomicReference<>(deadline.minusNanos(1000));
        doAnswer(invocation -> databaseNow.get()).when(orders).findCurrentTimeInTokyo();
        Map<String,Object> before=state(f);
        CompletableFuture<Integer> blocker=new CompletableFuture<>();
        // Test-only holder never requests Order, so it cannot invert production lock order.
        Future<?> holding=workers.submit(() -> tx(() -> {
            payments.findByOrderIdForUpdate(f.orderId);
            blocker.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
            awaitUnchecked(release);return null;
        }));
        int pid=blocker.get(20,TimeUnit.SECONDS);
        Future<?> cancelling=workers.submit(() -> userCancelStart.start(f.orderId,f.userId,"user"));
        assertWait(pid);
        databaseNow.set(deadline);
        release.countDown();holding.get(20,TimeUnit.SECONDS);
        assertThatThrownBy(() -> cancelling.get(20,TimeUnit.SECONDS)).hasCauseInstanceOf(InvalidOrderStatusException.class);
        assertThat(state(f)).isEqualTo(before);
        verifyNoInteractions(gateway,calculator);
    }

    private void prepare(Fixture f) {
        jdbc.update("update orders set shipping_preparation_started_at=timezone('Asia/Tokyo',clock_timestamp()), shipping_preparation_started_by_account_id=1, shipping_preparation_started_by_username='admin' where id=?",f.orderId);
    }
    private OrderShippingAddressForm address(String name) {
        OrderShippingAddressForm form=new OrderShippingAddressForm();form.setShippingName(name);
        form.setShippingPostalCode("100-0001");form.setShippingPrefecture("Tokyo");form.setShippingCity("Chiyoda");
        form.setShippingAddressLine("1-1");form.setShippingPhone("09012345678");return form;
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
        for(String table:List.of("order_content_change_histories","stock_movements","order_status_histories","order_shipping_address_histories")) {
            state.put(table,jdbc.queryForList("select * from "+table+" where order_id=? order by id",f.orderId));
        }
        state.put("payments",jdbc.queryForList("select * from payments where order_id=? order by id",f.orderId));
        state.put("transactions",jdbc.queryForList("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.id",f.orderId));
        return state;
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
            o.setShippingAddress("Original","100-0001","Tokyo","Chiyoda","1-1","09012345678");
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
