package com.example.ecsite.service.warehouse;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.OrderStatusHistoryService;
import com.example.ecsite.service.orderchange.OrderIncreaseService;
import com.example.ecsite.service.warehouse.WarehouseExportHoldException.Reason;
import com.example.ecsite.service.warehouse.WarehouseExportOrderTransactions.Status;

/**
 * Feature131 C-1: warehouse export lines and automatic shipping preparation (design doc ch.4 / ch.8).
 * LEGACY fixtures get a 14:00 deadline on a distinct past business date, so the real database clock is always
 * after 14:05 of that date and every test owns its batch.
 */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false",
        "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false"})
@com.example.ecsite.support.CaptureConnectionTest
class WarehouseExportPostgresTest {
    @Autowired WarehouseExportService service;
    @Autowired WarehouseExportOrderTransactions transactions;
    @Autowired WarehouseExportBatchStore batches;
    @Autowired OrderIncreaseService increases;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired TaxCategoryRepository taxes;
    @Autowired ProductRepository products;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository paymentTransactions;
    @Autowired OrderStatusHistoryService histories;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean PaymentGateway gateway;

    private static final AtomicInteger DAYS_AGO = new AtomicInteger(1000 + new Random().nextInt(20000));

    record Fixture(long order, long user, long item, long payment, LocalDate businessDate) {}

    <T> T tx(Supplier<T> body) { return new TransactionTemplate(manager).execute(s -> body.get()); }

    LocalDate nextBusinessDate() {
        return jdbc.queryForObject("select (timezone('Asia/Tokyo',clock_timestamp()))::date - ?", LocalDate.class,
                DAYS_AGO.addAndGet(3));
    }

    /** Card order (AUTHORIZED PAY.JP payment) whose deadline is 14:00 of {@code businessDate}. */
    Fixture card(LocalDate businessDate) { return order(businessDate, true); }

    Fixture order(LocalDate businessDate, boolean card) {
        Fixture f = tx(() -> {
            var now = orders.findCurrentTimeInTokyo(); String key = UUID.randomUUID().toString();
            var user = new User(); user.setUsername("f131-" + key); user.setPassword("unused"); user.setEnabled(true);
            user.setCreatedAt(now); user.setUpdatedAt(now); users.saveAndFlush(user);
            var category = categories.saveAndFlush(new Category(key)); var tax = taxes.findAll().getFirst();
            var product = new Product(); product.setName("item"); product.setPrice(900); product.setStock(20);
            product.setCategory(category); product.setTaxCategory(tax); products.saveAndFlush(product);
            int price = 100, quantity = 2, total = price * quantity + 550;
            var order = new Order(user.getId(), total, now, now.plusHours(1));
            order.setShippingAddress("山田 太郎", "100-0001", "東京都", "千代田区", "千代田1-1", "0312345678");
            order.addItem(new OrderItem(product.getId(), "ordered", category.getId(), category.getName(), tax.getId(), tax.getCode(),
                    tax.getName(), tax.getTaxRate(), price, quantity));
            order.addCharge(new OrderCharge(OrderChargeType.SHIPPING, "送料・梱包料", 550, tax.getId(), tax.getCode(), tax.getName(),
                    tax.getTaxRate(), 10));
            orders.saveAndFlush(order);
            histories.record(order, null, OrderStatus.ORDERED, OrderStatusHistoryActorType.USER, user.getId(), user.getUsername(), null);
            long paymentId = 0;
            if (card) {
                var payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, total, now);
                payment.setProviderPaymentId("pfw_" + key, now); payment.markAuthorized(now); payments.saveAndFlush(payment);
                var auth = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE, total, 0, key, now,
                        PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
                auth.markSucceeded(payment.getProviderPaymentId(), now); paymentTransactions.saveAndFlush(auth);
                paymentId = payment.getId();
            }
            return new Fixture(order.getId(), user.getId(), order.getItems().getFirst().getId(), paymentId, businessDate);
        });
        moveDeadline(f.order(), businessDate.atTime(14, 0));
        return f;
    }

    void moveDeadline(long order, LocalDateTime deadline) {
        jdbc.update("update orders set ordered_at=?, change_deadline_at=? where id=?", deadline.minusHours(2), deadline, order);
    }

    long batch(LocalDate date) { return batches.openOrGet(date).id(); }

    Map<String, Object> orderRow(long order) { return jdbc.queryForMap("select * from orders where id=?", order); }

    int lines(long order) { return jdbc.queryForObject("select count(*) from warehouse_export_lines where order_id=?", Integer.class, order); }

    void assertUnprepared(long order) {
        assertThat(orderRow(order)).containsEntry("shipping_preparation_started_at", null)
                .containsEntry("shipping_preparation_started_by_username", null);
        assertThat(lines(order)).isZero();
    }

    void assertHeld(Fixture f, long batch, Reason reason) {
        var outcome = service.export(batch, f.order());
        assertThat(outcome.isExported()).isFalse();
        assertThat(outcome.held()).isEqualTo(reason);
        assertUnprepared(f.order());
    }

    static void rejects(String sqlState, ThrowingCallable call) {
        assertThatThrownBy(call).satisfies(e -> {
            Throwable t = e;
            while (t != null && !(t instanceof java.sql.SQLException)) t = t.getCause();
            assertThat(t).as("SQLException in %s", e).isNotNull();
            assertThat(((java.sql.SQLException) t).getSQLState()).as("%s", t).isEqualTo(sqlState);
        });
    }

    @Test void exportsACardOrderAndStartsItsPreparationInTheSameCommit() {
        var f = card(nextBusinessDate()); long batch = batch(f.businessDate());

        var outcome = service.export(batch, f.order());

        assertThat(outcome.isExported()).isTrue();
        var line = outcome.exported();
        assertThat(line.status()).isEqualTo(Status.EXPORTED);
        assertThat(line.batchId()).isEqualTo(batch);
        assertThat(line.paymentId()).isEqualTo(f.payment());
        var order = orderRow(f.order());
        assertThat(order).containsEntry("status", "ORDERED").containsEntry("shipping_preparation_started_by_account_id", null)
                .containsEntry("shipping_preparation_started_by_username", Order.WAREHOUSE_EXPORT_PREPARER);
        assertThat(((java.sql.Timestamp) order.get("shipping_preparation_started_at")).toLocalDateTime()).isEqualTo(line.preparedAt());
        var stored = jdbc.queryForMap("select * from warehouse_export_lines where order_id=?", f.order());
        assertThat(stored).containsEntry("order_status", "ORDERED").containsEntry("total_amount", 750)
                .containsEntry("payment_amount", 750).containsEntry("shipping_name", "山田 太郎")
                .containsEntry("shipping_phone", "0312345678");
        assertThat(jdbc.queryForObject("select items->0->>'quantity' from warehouse_export_lines where order_id=?", String.class, f.order()))
                .isEqualTo("2");
        assertThat(jdbc.queryForObject("select (items->0->>'orderItemId')::bigint from warehouse_export_lines where order_id=?", Long.class, f.order()))
                .isEqualTo(f.item());
        // Capture/shipping code accepts the automatic preparation as complete.
        assertThat(orders.findById(f.order()).orElseThrow().hasCompleteShippingPreparation()).isTrue();
    }

    @Test void aRetryReturnsTheSameLineAndSealingFixesTheLineCount() {
        var date = nextBusinessDate(); var f = card(date); var g = card(date); long batch = batch(date);
        var first = service.export(batch, f.order()).exported();
        assertThat(service.export(batch, g.order()).isExported()).isTrue();

        var retry = service.export(batch, f.order()).exported();
        assertThat(retry.status()).isEqualTo(Status.ALREADY_EXPORTED);
        assertThat(retry.lineId()).isEqualTo(first.lineId());
        assertThat(retry.preparedAt()).isEqualTo(first.preparedAt());
        assertThat(batches.openOrGet(date).id()).isEqualTo(batch);

        var sealed = batches.seal(batch);
        assertThat(sealed.state()).isEqualTo(WarehouseExportBatchStore.State.SEALED);
        assertThat(sealed.lineCount()).isEqualTo(2);
        assertThat(sealed.sealedAt()).isAfterOrEqualTo(sealed.openedAt());
        assertThat(batches.seal(batch)).isEqualTo(sealed);

        var late = card(date);
        assertThatThrownBy(() -> service.export(batch, late.order())).isInstanceOf(IllegalStateException.class);
        assertUnprepared(late.order());
        rejects("23514", () -> jdbc.update("insert into warehouse_export_lines(batch_id,order_id) values (?,?)", batch, late.order()));
    }

    @Test void aPaidOrderWithoutACardPaymentIsExportedWithoutAPayment() {
        var f = order(nextBusinessDate(), false); long batch = batch(f.businessDate());
        jdbc.update("update orders set status='PAID', paid_at=ordered_at where id=?", f.order());

        var line = service.export(batch, f.order()).exported();

        assertThat(line.paymentId()).isNull();
        assertThat(jdbc.queryForMap("select order_status,payment_amount from warehouse_export_lines where id=?", line.lineId()))
                .containsEntry("order_status", "PAID").containsEntry("payment_amount", null);
    }

    @Test void pendingPaymentWorkHoldsTheOrder() {
        var f = card(nextBusinessDate()); long batch = batch(f.businessDate());
        tx(() -> {
            var payment = payments.findById(f.payment()).orElseThrow();
            paymentTransactions.saveAndFlush(new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE, 750, 0,
                    UUID.randomUUID().toString(), orders.findCurrentTimeInTokyo(), PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION));
            return null;
        });
        assertHeld(f, batch, Reason.PAYMENT_NOT_READY);
    }

    /** Design doc ch.7/8: a late-succeeded new authorization next to the original one blocks the export. */
    @Test void aSecondValidAuthorizationHoldsTheOrder() {
        var f = card(nextBusinessDate()); long batch = batch(f.businessDate());
        tx(() -> {
            var now = orders.findCurrentTimeInTokyo(); var order = orders.findById(f.order()).orElseThrow();
            var late = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, now);
            late.setProviderPaymentId("pfw_late_" + f.order(), now); late.markAuthorized(now); payments.saveAndFlush(late);
            return null;
        });
        assertHeld(f, batch, Reason.PAYMENT_NOT_READY);
    }

    @Test void anUnresolvedOrderChangeHoldsTheOrder() {
        var f = card(nextBusinessDate()); long batch = batch(f.businessDate());
        // Accept a change while the deadline is open, then move the order and the operation into the past.
        var deadline = f.businessDate().atTime(14, 0);
        jdbc.update("update orders set change_deadline_at=timezone('Asia/Tokyo',clock_timestamp())+interval '1 hour' where id=?", f.order());
        int revision = jdbc.queryForObject("select content_revision from orders where id=?", Integer.class, f.order());
        var op = increases.accept(new OrderIncreaseService.Request(f.order(), f.user(), UUID.randomUUID(), revision, Map.of(f.item(), 3)));
        moveDeadline(f.order(), deadline);
        jdbc.update("update order_change_operations set accepted_at=?,deadline_at=?,grace_until=? where id=?",
                deadline.minusMinutes(30), deadline, deadline.plusMinutes(10), op.id());

        assertHeld(f, batch, Reason.ORDER_CHANGE_UNRESOLVED);
    }

    @Test void manualPreparationCancellationAndDeadlinesAreRespected() {
        var date = nextBusinessDate(); long batch = batch(date);
        var manual = card(date);
        jdbc.update("update orders set shipping_preparation_started_at=change_deadline_at+interval '10 minutes',"
                + "shipping_preparation_started_by_account_id=7,shipping_preparation_started_by_username='first' where id=?", manual.order());
        var outcome = service.export(batch, manual.order());
        assertThat(outcome.held()).isEqualTo(Reason.MANUALLY_PREPARED);
        assertThat(lines(manual.order())).isZero();

        var cancelled = card(date);
        jdbc.update("update orders set status='CANCELLED', cancelled_at=ordered_at where id=?", cancelled.order());
        assertHeld(cancelled, batch, Reason.NOT_EXPORTABLE_STATUS);

        var nextDay = card(date.plusDays(1));
        assertHeld(nextDay, batch, Reason.NOT_DUE);

        var open = card(date);
        jdbc.update("update orders set change_deadline_at=timezone('Asia/Tokyo',clock_timestamp())+interval '1 hour' where id=?", open.order());
        assertHeld(open, batch, Reason.NOT_DUE);
    }

    @Test void candidatesAreUnpreparedDueOrdersFromTheStartDate() {
        var date = nextBusinessDate(); var due = card(date); var older = card(date.minusDays(1)); var tooOld = card(date.minusDays(2));
        var later = card(date.plusDays(1)); var manual = card(date);
        jdbc.update("update orders set shipping_preparation_started_at=change_deadline_at+interval '10 minutes',"
                + "shipping_preparation_started_by_account_id=7,shipping_preparation_started_by_username='first' where id=?", manual.order());

        var found = batches.candidates(date, date.minusDays(1), 1000);

        assertThat(found).contains(due.order(), older.order())
                .doesNotContain(tooOld.order(), later.order(), manual.order());
        assertThat(found.indexOf(older.order())).isLessThan(found.indexOf(due.order()));
    }

    @Test void theDatabaseRequiresTheLineForAnAutomaticPreparation() {
        var f = card(nextBusinessDate()); batch(f.businessDate());
        rejects("23514", () -> tx(() -> jdbc.update("update orders set shipping_preparation_started_at=timezone('Asia/Tokyo',clock_timestamp()),"
                + "shipping_preparation_started_by_username='WAREHOUSE_EXPORT' where id=?", f.order())));
        assertUnprepared(f.order());
        rejects("23514", () -> jdbc.update("insert into warehouse_export_lines(batch_id,order_id) values ((select id from warehouse_export_batches where business_date=?),?)",
                f.businessDate(), f.order()));
        rejects("23514", () -> jdbc.update("update orders set shipping_preparation_started_at=change_deadline_at,"
                + "shipping_preparation_started_by_username='someone' where id=?", f.order()));
        // A NULL preparer name must not slip through the CHECK as "unknown".
        assertThatThrownBy(() -> jdbc.update("update orders set shipping_preparation_started_at=change_deadline_at where id=?", f.order()))
                .hasMessageContaining("chk_orders_shipping_preparation_metadata");
        assertUnprepared(f.order());
    }

    @Test void aPreparedOrderIsFrozenAndHistoryIsAppendOnly() {
        var f = card(nextBusinessDate()); long batch = batch(f.businessDate());
        var line = service.export(batch, f.order()).exported();

        for (String sql : List.of(
                "update orders set total_amount=total_amount+1 where id=?",
                "update orders set content_revision=content_revision+1 where id=?",
                "update orders set shipping_name='別人' where id=?",
                "update orders set active_payment_id=(select min(id) from payments where order_id=orders.id) where id=?",
                "update orders set status='CANCELLED', cancelled_at=ordered_at where id=?",
                "update orders set shipping_preparation_started_at=null, shipping_preparation_started_by_username=null where id=?",
                "update order_items set quantity=quantity+1 where order_id=?",
                "delete from order_charges where order_id=?")) {
            rejects("55000", () -> jdbc.update(sql, f.order()));
        }
        jdbc.update("update orders set handling_status=handling_status where id=?", f.order());
        rejects("55000", () -> jdbc.update("update warehouse_export_lines set total_amount=0 where id=?", line.lineId()));
        rejects("55000", () -> jdbc.update("delete from warehouse_export_lines where id=?", line.lineId()));
        rejects("55000", () -> jdbc.update("delete from warehouse_export_batches where id=?", batch));
        batches.seal(batch);
        rejects("55000", () -> jdbc.update("update warehouse_export_batches set state='OPEN', sealed_at=null, line_count=null where id=?", batch));
        rejects("23514", () -> jdbc.update("insert into warehouse_export_batches(business_date) values ((timezone('Asia/Tokyo',clock_timestamp()))::date + 1)"));
        assertThat(lines(f.order())).isEqualTo(1);
    }

    @Test void concurrentExportsOfOneOrderWriteOneLine() throws Exception {
        var f = card(nextBusinessDate()); long batch = batch(f.businessDate());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<WarehouseExportService.Outcome> call = () -> { start.await(); return service.export(batch, f.order()); };
            var a = pool.submit(call); var b = pool.submit(call);
            start.countDown();
            var statuses = List.of(a.get(30, TimeUnit.SECONDS).exported().status(), b.get(30, TimeUnit.SECONDS).exported().status());
            assertThat(statuses).containsExactlyInAnyOrder(Status.EXPORTED, Status.ALREADY_EXPORTED);
            assertThat(lines(f.order())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void sealingWaitsForAnInFlightExport() throws Exception {
        var f = card(nextBusinessDate()); long batch = batch(f.businessDate());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var exported = new CountDownLatch(1); var release = new CountDownLatch(1);
            var writer = pool.submit(() -> tx(() -> {
                transactions.exportOrder(batch, f.order());
                exported.countDown();
                try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return null;
            }));
            assertThat(exported.await(30, TimeUnit.SECONDS)).isTrue();
            var sealer = pool.submit(() -> batches.seal(batch));
            Thread.sleep(500);
            assertThat(sealer.isDone()).as("seal must wait for the uncommitted line").isFalse();
            release.countDown();
            writer.get(30, TimeUnit.SECONDS);
            assertThat(sealer.get(30, TimeUnit.SECONDS).lineCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void preparationActorPredicateAcceptsOnlyAdministratorsOrTheWarehouseExport() {
        assertThat(Order.isValidShippingPreparationActor(7L, "first")).isTrue();
        assertThat(Order.isValidShippingPreparationActor(null, Order.WAREHOUSE_EXPORT_PREPARER)).isTrue();
        assertThat(Order.isValidShippingPreparationActor(null, "first")).isFalse();
        assertThat(Order.isValidShippingPreparationActor(0L, "first")).isFalse();
        assertThat(Order.isValidShippingPreparationActor(7L, " ")).isFalse();
    }
}
