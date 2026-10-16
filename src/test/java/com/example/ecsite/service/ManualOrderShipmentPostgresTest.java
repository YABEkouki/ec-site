package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import org.hibernate.Session;
import org.hibernate.FlushMode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

/** Real services, locks and MVC/security. Only the isolated launcher supplies the PostgreSQL database. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ManualOrderShipmentPostgresTest {
    @Autowired OrderService service;
    @Autowired OrderShippingPreparationService preparation;
    @MockitoSpyBean OrderRepository orders;
    @MockitoSpyBean OrderStatusHistoryService histories;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxes;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean PaymentGateway gateway;
    private ExecutorService workers;
    private final CountDownLatch release = new CountDownLatch(1);
    @BeforeEach void setup() { workers=Executors.newFixedThreadPool(2); authenticate(9L,"shipper"); }
    @AfterEach void cleanup() throws Exception {
        release.countDown(); workers.shutdownNow();
        try { assertThat(workers.awaitTermination(25,TimeUnit.SECONDS)).isTrue(); verifyNoInteractions(gateway); }
        finally { SecurityContextHolder.clearContext(); }
    }
    private AdminUserDetails actor(long id,String username) {
        return new AdminUserDetails(id,username,"password",true,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
    private void authenticate(long id,String username) {
        var actor=actor(id,username);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,"password",actor.getAuthorities()));
    }
    private <T> T asAdmin(long id,String name,Supplier<T> call) {
        try { authenticate(id,name); return call.get(); } finally { SecurityContextHolder.clearContext(); }
    }
    private <T> T tx(Supplier<T> call) { return new TransactionTemplate(manager).execute(s -> call.get()); }
    private LocalDateTime now() { return jdbc.queryForObject("select clock_timestamp() AT TIME ZONE 'Asia/Tokyo'",LocalDateTime.class); }
    private int pid() { return jdbc.queryForObject("select pg_backend_pid()",Integer.class); }
    private void ship(Fixture f) { service.markAsShipped(f.id(),9L,"shipper","shipment-note"); }
    private Fixture fixture(boolean prepared, String status, PaymentStatus paymentStatus) {
        return tx(() -> {
            var now=now(); String unique=UUID.randomUUID().toString();
            User user=new User(); user.setUsername("manual-ship-"+unique); user.setPassword("password");
            user.setEnabled(true); user.setCreatedAt(now); user.setUpdatedAt(now); users.saveAndFlush(user);
            Category category=categories.saveAndFlush(new Category("ship-"+unique)); var tax=taxes.findAll().getFirst();
            Product product=new Product(); product.setName("shipment fixture"); product.setPrice(100);
            product.setStock(10); product.setCategory(category); product.setTaxCategory(tax); products.saveAndFlush(product);
            var ordered=now.minusDays(1).toLocalDate().atTime(10,0);
            Order order=new Order(user.getId(),200,ordered,ordered.withHour(14));
            order.addItem(new OrderItem(product.getId(),product.getName(),category.getId(),category.getName(),
                    tax.getId(),tax.getCode(),tax.getName(),tax.getTaxRate(),100,2));
            if (!status.equals("ORDERED")) order.markAsPaid();
            ReflectionTestUtils.setField(order,"status",OrderStatus.valueOf(status));
            if (prepared) order.beginShippingPreparation(ordered.withHour(14),7L,"preparer");
            orders.saveAndFlush(order);
            Long paymentId=null;
            if (paymentStatus!=null) {
                Payment payment=new Payment(order,PaymentProvider.PAYJP,PaymentMethod.CARD,200,now);
                payment.setProviderPaymentId("ship_"+unique,now); ReflectionTestUtils.setField(payment,"status",paymentStatus);
                payments.saveAndFlush(payment); paymentId=payment.getId();
                PaymentTransaction authorization=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,200,0,unique,now);
                authorization.markSucceeded(payment.getProviderPaymentId(),now); transactions.saveAndFlush(authorization);
            }
            return new Fixture(order.getId(),product.getId(),paymentId);
        });
    }
    private Fixture fixture() { return fixture(true,"PAID",null); }
    private Map<String,Object> state(Fixture f) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("order",jdbc.queryForMap("select * from orders where id=?",f.id()));
        for (String table:List.of("order_items","order_charges","payments","order_status_histories","stock_movements","order_content_change_histories"))
            result.put(table,jdbc.queryForList("select * from "+table+" where order_id=? order by id",f.id()));
        result.put("transactions",jdbc.queryForList("select t.* from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=? order by t.id",f.id()));
        result.put("stock",jdbc.queryForMap("select * from products where id=?",f.productId()));
        return result;
    }
    private Map<String,Object> orderRow(Fixture f) { return jdbc.queryForMap("select * from orders where id=?",f.id()); }
    private int historyCount(Fixture f) { return jdbc.queryForObject("select count(*) from order_status_histories where order_id=?",Integer.class,f.id()); }
    private void rejected(Fixture f) {
        var before=state(f); assertThatThrownBy(() -> ship(f)).isInstanceOf(InvalidOrderStatusException.class);
        assertThat(state(f)).isEqualTo(before);
    }
    @Test void preparedPaidShipmentPersistsOnlyShipmentAndHistoryUsingTokyoDbTime() {
        Fixture f=fixture(); var before=state(f); var beforeOrder=orderRow(f); var earliest=now(); ship(f); var latest=now();
        var after=state(f); var row=orderRow(f);
        LocalDateTime shipped=((java.sql.Timestamp)row.get("shipped_at")).toLocalDateTime();
        assertThat(shipped).isBetween(earliest,latest); assertThat(shipped.getNano()%1000).isZero();
        assertThat(row).containsEntry("status","SHIPPED").containsEntry("shipped_by_account_id",9L)
                .containsEntry("shipped_by_username","shipper").containsEntry("shipment_capture_payment_id",null);
        assertThat(row.get("paid_at")).isEqualTo(beforeOrder.get("paid_at"));
        assertThat(row.get("shipping_preparation_started_by_account_id")).isEqualTo(7L);
        for (String key:List.of("status","shipped_at","shipped_by_account_id","shipped_by_username")) beforeOrder.put(key,row.get(key));
        assertThat(row).isEqualTo(beforeOrder);
        before.remove("order"); after.remove("order"); before.remove("order_status_histories"); after.remove("order_status_histories");
        assertThat(after).isEqualTo(before); assertThat(historyCount(f)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select from_status,to_status,changed_by_account_id,changed_by_username,internal_note from order_status_histories where order_id=?",f.id()))
                .containsEntry("from_status","PAID").containsEntry("to_status","SHIPPED").containsEntry("changed_by_account_id",9L)
                .containsEntry("changed_by_username","shipper").containsEntry("internal_note","shipment-note");
        tx(() -> { assertThat(orders.findById(f.id()).orElseThrow().getShippedAt()).isEqualTo(shipped); return null; });
    }
    @Test void unpreparedPaidIsRejected() { rejected(fixture(false,"PAID",null)); }
    @ParameterizedTest @ValueSource(strings={"ORDERED","SHIPPED","CANCELLED"})
    void wrongOrderStatusIsRejected(String status) { rejected(fixture(true,status,null)); }
    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"PENDING","REQUIRES_ACTION","AUTHORIZED"})
    void manualPaymentIneligibilityIsPreserved(PaymentStatus status) { rejected(fixture(true,"PAID",status)); }
    @ParameterizedTest @EnumSource(value=PaymentTransactionType.class,names={"AUTHORIZE","CAPTURE","CANCEL"})
    void pendingTransactionIsRejected(PaymentTransactionType type) {
        Fixture f=fixture(true,"PAID",PaymentStatus.CAPTURED);
        tx(() -> { transactions.saveAndFlush(new PaymentTransaction(payments.findById(f.paymentId()).orElseThrow(),type,200,0,UUID.randomUUID().toString(),now())); return null; });
        rejected(f);
    }
    @Test void simultaneousShipmentsSerializeAndOnlyOneHistoryIsCreated() throws Exception {
        Fixture f=fixture(); var ready=new CompletableFuture<Integer>();
        Future<?> first=workers.submit(() -> asAdmin(9,"shipper",() -> tx(() -> { ship(f); ready.complete(pid()); await(release); return null; })));
        int blocker=ready.get(20,TimeUnit.SECONDS);
        Future<Boolean> second=workers.submit(() -> asAdmin(9,"shipper",() -> {
            try { ship(f); return true; } catch (InvalidOrderStatusException e) { return false; }
        }));
        blockedBy(blocker); release.countDown(); first.get(20,TimeUnit.SECONDS);
        assertThat(second.get(20,TimeUnit.SECONDS)).isFalse(); assertThat(historyCount(f)).isEqualTo(1);
    }
    @Test void preparationWinsOrderLockThenWaitingShipmentUsesNewMetadata() throws Exception {
        Fixture f=fixture(false,"PAID",null); var ready=new CompletableFuture<Integer>();
        Future<?> first=workers.submit(() -> asAdmin(7,"preparer",() -> tx(() -> { preparation.start(f.id()); ready.complete(pid()); await(release); return null; })));
        int blocker=ready.get(20,TimeUnit.SECONDS);
        Future<?> shipment=workers.submit(() -> asAdmin(9,"shipper",() -> { ship(f); return null; }));
        blockedBy(blocker); release.countDown(); first.get(20,TimeUnit.SECONDS); shipment.get(20,TimeUnit.SECONDS);
        assertThat(orderRow(f)).containsEntry("status","SHIPPED").containsEntry("shipping_preparation_started_by_account_id",7L);
        assertThat(historyCount(f)).isEqualTo(1);
    }
    @Test void cancellationWinsOrderLockThenShipmentRejectsLatestCancelledState() throws Exception {
        Fixture f=fixture(false,"ORDERED",null);
        jdbc.update("update orders set change_deadline_at=? where id=?",now().plusDays(1),f.id());
        var ready=new CompletableFuture<Integer>();
        Future<?> first=workers.submit(() -> asAdmin(9,"shipper",() -> tx(() -> {
            service.cancelOrder(f.id(),9L,"shipper","cancel"); ready.complete(pid()); await(release); return null;
        })));
        int blocker=ready.get(20,TimeUnit.SECONDS);
        Future<Boolean> shipment=workers.submit(() -> asAdmin(9,"shipper",() -> { try { ship(f); return true; } catch (InvalidOrderStatusException e) { return false; } }));
        blockedBy(blocker); release.countDown(); first.get(20,TimeUnit.SECONDS);
        assertThat(shipment.get(20,TimeUnit.SECONDS)).isFalse(); assertThat(orderRow(f)).containsEntry("status","CANCELLED");
        assertThat(historyCount(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.productId())).isEqualTo(12);
    }
    @Test void shipmentWinsThenCancellationCannotUndoIt() throws Exception {
        Fixture f=fixture(); var ready=new CompletableFuture<Integer>();
        Future<?> first=workers.submit(() -> asAdmin(9,"shipper",() -> tx(() -> { ship(f); ready.complete(pid()); await(release); return null; })));
        int blocker=ready.get(20,TimeUnit.SECONDS);
        Future<Boolean> cancel=workers.submit(() -> asAdmin(9,"shipper",() -> { try { service.cancelOrder(f.id(),9L,"shipper","cancel"); return true; } catch (InvalidOrderStatusException e) { return false; } }));
        blockedBy(blocker); release.countDown(); first.get(20,TimeUnit.SECONDS);
        assertThat(cancel.get(20,TimeUnit.SECONDS)).isFalse(); assertThat(historyCount(f)).isEqualTo(1);
        assertThat(orderRow(f)).containsEntry("status","SHIPPED");
        assertThat(jdbc.queryForObject("select stock from products where id=?",Integer.class,f.productId())).isEqualTo(10);
    }
    @ParameterizedTest @ValueSource(strings={"prepared","cancelled","shipped","payment","new-pending","completed-pending"})
    void preloadedContextUsesOtherCommittedTransactionChanges(String change) throws Exception {
        Fixture f=fixture(!change.equals("prepared"),"PAID",PaymentStatus.CAPTURED);
        Long pending=change.equals("completed-pending") ? tx(() -> transactions.saveAndFlush(new PaymentTransaction(
                payments.findById(f.paymentId()).orElseThrow(),PaymentTransactionType.CAPTURE,200,0,UUID.randomUUID().toString(),now())).getId()) : null;
        var ready=new CompletableFuture<Integer>(); var committed=new CountDownLatch(1);
        Future<Boolean> result=workers.submit(() -> asAdmin(9,"shipper",() -> {
            try {
                return tx(() -> {
                    Order loaded=em.find(Order.class,f.id()); Payment payment=em.find(Payment.class,f.paymentId());
                    transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(f.paymentId());
                    ready.complete(pid()); await(committed);
                    ship(f);
                    assertThat(em.find(Order.class,f.id())).isSameAs(loaded);
                    assertThat(em.find(Payment.class,f.paymentId())).isSameAs(payment);
                    return true;
                });
            } catch (InvalidOrderStatusException e) { return false; }
        }));
        int firstPid=ready.get(20,TimeUnit.SECONDS);
        try {
            tx(() -> {
                assertThat(pid()).isNotEqualTo(firstPid);
                orders.findByIdForUpdate(f.id()).orElseThrow(); payments.findByOrderIdForUpdate(f.id());
                switch(change) {
                    case "prepared" -> em.find(Order.class,f.id()).beginShippingPreparation(now(),7L,"preparer");
                    case "cancelled" -> jdbc.update("update orders set status='CANCELLED' where id=?",f.id());
                    case "shipped" -> ship(f);
                    case "payment" -> jdbc.update("update payments set status='AUTHORIZED' where id=?",f.paymentId());
                    case "new-pending" -> transactions.saveAndFlush(new PaymentTransaction(em.find(Payment.class,f.paymentId()),PaymentTransactionType.CAPTURE,200,0,UUID.randomUUID().toString(),now()));
                    case "completed-pending" -> em.find(PaymentTransaction.class,pending).markSucceeded("result",now());
                }
                return null;
            });
        } finally { committed.countDown(); }
        boolean expected=change.equals("prepared")||change.equals("completed-pending");
        assertThat(result.get(20,TimeUnit.SECONDS)).isEqualTo(expected);
        assertThat(historyCount(f)).isEqualTo(expected||change.equals("shipped")?1:0);
    }
    @ParameterizedTest @ValueSource(strings={"unchanged","authorization","pending"})
    void paymentLockWaitRefreshesStateBeforeDbTime(String change) throws Exception {
        Fixture f=fixture(true,"PAID",PaymentStatus.CAPTURED); var ready=new CompletableFuture<Integer>();
        Future<?> blocker=workers.submit(() -> tx(() -> {
            Payment payment=payments.findByIdForUpdate(f.paymentId()).orElseThrow(); ready.complete(pid()); await(release);
            if(change.equals("authorization")) ReflectionTestUtils.setField(payment,"status",PaymentStatus.AUTHORIZED);
            if(change.equals("pending")) transactions.saveAndFlush(new PaymentTransaction(payment,PaymentTransactionType.CAPTURE,200,0,UUID.randomUUID().toString(),now()));
            return null;
        }));
        int blockerPid=ready.get(20,TimeUnit.SECONDS); clearInvocations(orders);
        Future<Boolean> shipment=workers.submit(() -> asAdmin(9,"shipper",() -> { try { ship(f); return true; } catch(InvalidOrderStatusException e) { return false; } }));
        blockedBy(blockerPid); verify(orders,never()).findCurrentTimeInTokyo(); var earliest=now(); release.countDown();
        blocker.get(20,TimeUnit.SECONDS); assertThat(shipment.get(20,TimeUnit.SECONDS)).isEqualTo(change.equals("unchanged"));
        if(change.equals("unchanged")) {
            verify(orders).findCurrentTimeInTokyo(); assertThat(((java.sql.Timestamp)orderRow(f).get("shipped_at")).toLocalDateTime()).isAfterOrEqualTo(earliest);
        } else { verify(orders,never()).findCurrentTimeInTokyo(); assertThat(historyCount(f)).isZero(); }
    }
    @ParameterizedTest @ValueSource(strings={"order","payment","transaction","product","partial"})
    void trackedDirtyChangesAreRejectedBeforeQueriesAndNotSilentlyDiscarded(String dirty) {
        Fixture f=fixture(true,"PAID",PaymentStatus.CAPTURED); var before=state(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Object changed;
            switch(dirty) {
                case "order" -> { Order order=em.find(Order.class,f.id()); order.setTotalAmount(999); changed=order; }
                case "partial" -> { Order order=em.find(Order.class,f.id()); ReflectionTestUtils.setField(order,"shippingPreparationStartedByUsername",null); changed=order; }
                case "payment" -> { Payment payment=em.find(Payment.class,f.paymentId()); ReflectionTestUtils.setField(payment,"amount",999); changed=payment; }
                case "transaction" -> { var transaction=transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(f.paymentId()).getFirst(); ReflectionTestUtils.setField(transaction,"amount",999); changed=transaction; }
                default -> { Product product=em.find(Product.class,f.productId()); product.setName("unflushed"); changed=product; }
            }
            Session session=em.unwrap(Session.class); assertThat(session.isDirty()).isTrue(); clearInvocations(orders);
            var statistics=em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
            boolean enabled=statistics.isStatisticsEnabled(); statistics.setStatisticsEnabled(true);
            long flushes=statistics.getFlushCount();
            try {
                assertThatIllegalStateException().isThrownBy(() -> ship(f));
                assertThat(statistics.getFlushCount()).isEqualTo(flushes);
            } finally { statistics.setStatisticsEnabled(enabled); }
            assertThat(status.isRollbackOnly()).isTrue();
            verify(orders,never()).findByIdForUpdate(f.id()); assertThat(session.contains(changed)).isTrue(); assertThat(session.isDirty()).isTrue();
            status.setRollbackOnly();
        });
        assertThat(state(f)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"readonly-tx","readonly-session","readonly-order","manual-flush","repeatable","serializable","db-readonly","db-isolation"})
    void unsupportedTransactionContextsAreRejectedWithoutChanges(String mode) {
        Fixture f=fixture(); var before=state(f); var template=new TransactionTemplate(manager);
        if(mode.equals("readonly-tx")) template.setReadOnly(true);
        if(mode.equals("repeatable")) template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        if(mode.equals("serializable")) template.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        template.executeWithoutResult(status -> {
            Session session=em.unwrap(Session.class);
            switch(mode) {
                case "readonly-session" -> session.setDefaultReadOnly(true);
                case "readonly-order" -> session.setReadOnly(em.find(Order.class,f.id()),true);
                case "manual-flush" -> session.setHibernateFlushMode(FlushMode.MANUAL);
                case "db-readonly" -> jdbc.execute("set transaction read only");
                case "db-isolation" -> jdbc.execute("set transaction isolation level repeatable read");
            }
            assertThatIllegalStateException().isThrownBy(() -> ship(f)); assertThat(status.isRollbackOnly()).isTrue();
            status.setRollbackOnly();
        });
        assertThat(state(f)).isEqualTo(before);
    }
    @Test void historyFailureRollsBackShipmentAndHistory() {
        Fixture f=fixture(); var before=state(f);
        doThrow(new IllegalStateException("history failure")).when(histories).record(any(Order.class),eq(OrderStatus.PAID),eq(OrderStatus.SHIPPED),any(),any(),any(),any());
        assertThatIllegalStateException().isThrownBy(() -> ship(f)); assertThat(state(f)).isEqualTo(before);
    }
    @Test void outerRollbackRollsBackCompleteShipment() {
        Fixture f=fixture(); var before=state(f);
        new TransactionTemplate(manager).executeWithoutResult(status -> { ship(f); status.setRollbackOnly(); });
        assertThat(state(f)).isEqualTo(before);
    }
    @Test void dbClockFailureDoesNotPersistShipment() {
        Fixture f=fixture(); var before=state(f); doThrow(new IllegalStateException("clock failure")).when(orders).findCurrentTimeInTokyo();
        assertThatIllegalStateException().isThrownBy(() -> ship(f)); assertThat(state(f)).isEqualTo(before);
    }
    @Test void csrfAndAdminSecurityArePreservedWithoutMutationsOnRejection() throws Exception {
        SecurityContextHolder.clearContext();
        Fixture f=fixture(); var before=state(f); String url="/admin/orders/"+f.id()+"/ship";
        mvc.perform(post(url).with(csrf())).andExpect(status().isFound()).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post(url).with(user("user").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(actor(9,"shipper")))).andExpect(status().isForbidden());
        assertThat(state(f)).isEqualTo(before);
    }
    @Test void adminPostPrgPreservesReturnUrlAndRecordsAuthenticatedActor() throws Exception {
        Fixture f=fixture(); String returnUrl="/admin/orders?status=PAID&page=2&size=20&sort=id,desc";
        var post=mvc.perform(post("/admin/orders/"+f.id()+"/ship").with(user(actor(9,"shipper"))).with(csrf())
                        .param("returnUrl",returnUrl).param("internalNote","web-note").param("accountId","999").param("username","forged"))
                .andExpect(status().isFound()).andExpect(flash().attribute("successMessage","注文を発送済みに変更しました。")).andReturn();
        String html=mvc.perform(get(URI.create(post.getResponse().getRedirectedUrl())).with(user(actor(9,"shipper")))
                        .session((MockHttpSession)post.getRequest().getSession(false)))
                .andExpect(status().isOk()).andExpect(model().attribute("returnUrl",returnUrl)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("注文を発送済みに変更しました。","shipper","web-note");
        assertThat(orderRow(f)).containsEntry("shipped_by_account_id",9L); assertThat(historyCount(f)).isEqualTo(1);
    }
    @Test void adminUnpreparedPostKeepsExistingPrgErrorContract() throws Exception {
        Fixture f=fixture(false,"PAID",null); var before=state(f);
        mvc.perform(post("/admin/orders/"+f.id()+"/ship").with(user(actor(9,"shipper"))).with(csrf()))
                .andExpect(status().isFound()).andExpect(flash().attribute("errorMessage","発送準備を開始してから発送してください。"));
        assertThat(state(f)).isEqualTo(before);
    }
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(15,TimeUnit.SECONDS)).isTrue(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private void blockedBy(int blocker) throws InterruptedException {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<until) {
            if(!jdbc.queryForList("select pid from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid))",Integer.class,blocker).isEmpty()) return;
            new CountDownLatch(1).await(25,TimeUnit.MILLISECONDS);
        }
        fail("No independent PostgreSQL backend waited for %s",blocker);
    }
    private record Fixture(Long id,Long productId,Long paymentId) {}
}
