package com.example.ecsite.service.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.ManualOrderShipmentService;
import com.example.ecsite.service.OrderStatusHistoryService;
import com.example.ecsite.service.payment.PaymentCaptureService;
import com.example.ecsite.service.payment.PaymentReconciliationItemService;
import com.example.ecsite.service.payment.PaymentSynchronizationOutcome;

/**
 * Feature132-A: an exported order can be shipped by the warehouse shipment import as the system
 * (no logged-in administrator), and every shipment record then carries the same system shipper (V89).
 */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false",
        "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false"})
@com.example.ecsite.support.CaptureConnectionTest
class WarehouseShipmentSystemActorPostgresTest {
    static final String IMPORTER = Order.WAREHOUSE_SHIPMENT_IMPORTER;
    static final WarehouseShipmentActor SYSTEM = WarehouseShipmentActor.INSTANCE;

    @Autowired PaymentCaptureService capture;
    @Autowired ManualOrderShipmentService manualShipments;
    @Autowired PaymentReconciliationItemService reconciliation;
    @Autowired WarehouseExportService exports;
    @Autowired WarehouseExportBatchStore batches;
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

    @BeforeEach @AfterEach void noAdministrator() { SecurityContextHolder.clearContext(); }

    @Test void anExportedCardOrderShipsAndCapturesAsTheSystem() {
        var f = exported(true);
        when(gateway.capture(any())).thenReturn(captured(f));

        var result = capture.captureForWarehouseImport(f.order(), SYSTEM);

        assertThat(result.status()).isEqualTo(CaptureResultStatus.CAPTURED);
        assertSystemShipment(f, "SHIPPED");
        assertThat(order(f)).containsEntry("shipment_capture_payment_id", f.payment());
        assertThat(order(f).get("paid_at")).isNotNull();
        assertThat(captureRow(f)).containsEntry("status", "SUCCEEDED").containsEntry("initiator_type", "SYSTEM")
                .containsEntry("initiator_id", null).containsEntry("initiator_username", IMPORTER)
                .containsEntry("purpose", "SHIPMENT_CAPTURE").containsEntry("internal_note", null);
        assertThat(jdbc.queryForObject("select status from payments where id=?", String.class, f.payment())).isEqualTo("CAPTURED");

        // A repeated import of the same order is idempotent: no second capture or history.
        assertThat(capture.captureForWarehouseImport(f.order(), SYSTEM).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        assertThat(captures(f)).isEqualTo(1);
        assertThat(shippedHistories(f)).isEqualTo(1);
    }

    @Test void anUnknownCaptureResultStaysPendingAndConvergesThroughReconciliation() {
        var f = exported(true);
        when(gateway.capture(any())).thenThrow(new PaymentGatewayException("timeout", new RuntimeException()));

        assertThatThrownBy(() -> capture.captureForWarehouseImport(f.order(), SYSTEM)).isInstanceOf(RuntimeException.class);

        assertSystemShipment(f, "SHIPPED");
        assertThat(order(f).get("paid_at")).isNull();
        assertThat(captureRow(f)).containsEntry("status", "PENDING").containsEntry("initiator_type", "SYSTEM");

        when(gateway.retrievePaymentFlow(f.provider()))
                .thenReturn(new PaymentFlowState(f.provider(), PaymentFlowStatus.SUCCEEDED, null, null));
        long captureId = ((Number) captureRow(f).get("id")).longValue();
        assertThat(reconciliation.reconcile(captureId)).isEqualTo(PaymentSynchronizationOutcome.APPLIED);
        assertThat(captureRow(f)).containsEntry("status", "SUCCEEDED");
        assertThat(order(f).get("paid_at")).isNotNull();
    }

    @Test void anExportedPaidOrderShipsAsTheSystemWithoutACapture() {
        var f = exported(false);

        tx(() -> { manualShipments.shipAsWarehouseImport(f.order(), SYSTEM); return null; });

        assertSystemShipment(f, "SHIPPED");
        assertThat(order(f)).containsEntry("shipment_capture_payment_id", null);
        assertThat(jdbc.queryForObject("select from_status from order_status_histories where order_id=? and to_status='SHIPPED'",
                String.class, f.order())).isEqualTo("PAID");
    }

    @Test void onlyAnOrderPreparedByTheWarehouseExportShipsAsTheSystem() {
        var card = order(nextBusinessDate(), true);
        prepareByAdministrator(card.order());
        assertThatThrownBy(() -> capture.captureForWarehouseImport(card.order(), SYSTEM)).isInstanceOf(IllegalStateException.class);
        assertThat(order(card)).containsEntry("status", "ORDERED").containsEntry("shipped_by_username", null);

        var paid = order(nextBusinessDate(), false);
        jdbc.update("update orders set status='PAID', paid_at=ordered_at where id=?", paid.order());
        prepareByAdministrator(paid.order());
        assertThatThrownBy(() -> tx(() -> { manualShipments.shipAsWarehouseImport(paid.order(), SYSTEM); return null; }))
                .isInstanceOf(com.example.ecsite.exception.InvalidOrderStatusException.class);
        assertThat(order(paid)).containsEntry("status", "PAID");

        assertThatThrownBy(() -> capture.captureForWarehouseImport(card.order(), null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void anExportedOrderStillShipsByAnAdministrator() {
        var f = exported(true);
        authenticate(9, "shipper");
        when(gateway.capture(any())).thenReturn(captured(f));

        capture.captureForShipment(f.order(), 9L, "shipper", null);

        assertThat(order(f)).containsEntry("status", "SHIPPED").containsEntry("shipped_by_account_id", 9L)
                .containsEntry("shipped_by_username", "shipper");
        assertThat(captureRow(f)).containsEntry("initiator_type", "ADMIN").containsEntry("initiator_id", 9L);
    }

    @Test void theDatabaseOnlyAcceptsACompleteSystemShipmentOfAnExportedOrder() {
        String ship = "update orders set status='SHIPPED', shipped_at=timezone('Asia/Tokyo',clock_timestamp()),"
                + "shipped_by_account_id=?, shipped_by_username=?, shipment_capture_payment_id=? where id=?";

        // Only the warehouse export's preparation can carry a system shipper (CHECK).
        var manual = order(nextBusinessDate(), false);
        jdbc.update("update orders set status='PAID', paid_at=ordered_at where id=?", manual.order());
        prepareByAdministrator(manual.order());
        rejects("23514", () -> jdbc.update(ship, null, IMPORTER, null, manual.order()));
        rejects("23514", () -> jdbc.update(ship, null, "SOMEONE", null, manual.order()));

        // A card shipment needs exactly one system shipment capture on the shipped payment (trigger).
        var card = exported(true);
        rejects("23514", () -> jdbc.update(ship, null, IMPORTER, card.payment(), card.order()));

        // System capture and SHIPPED history rows always carry the fixed system shipper (CHECK).
        rejects("23514", () -> jdbc.update("insert into payment_transactions(payment_id,transaction_type,status,amount,"
                + "order_content_revision,idempotency_key,created_at,initiator_type,initiator_id,initiator_username,purpose) "
                + "values (?,'CAPTURE','PENDING',750,0,?,localtimestamp,'SYSTEM',null,'SOMEONE','SHIPMENT_CAPTURE')",
                card.payment(), UUID.randomUUID().toString()));
        rejects("23514", () -> jdbc.update("insert into order_status_histories(order_id,from_status,to_status,changed_at,"
                + "changed_by_type,changed_by_account_id,changed_by_username) values (?,'ORDERED','SHIPPED',localtimestamp,'SYSTEM',null,'SOMEONE')",
                card.order()));

        // A recorded system shipper never changes, and an administrator shipment never becomes one.
        var paid = exported(false);
        jdbc.update(ship, null, IMPORTER, null, paid.order());
        assertSystemShipmentRow(paid);
        rejects("55000", () -> jdbc.update("update orders set shipped_by_account_id=7, shipped_by_username='admin' where id=?", paid.order()));
        var adminShipped = exported(false);
        jdbc.update(ship, 7L, "admin", null, adminShipped.order());
        rejects("55000", () -> jdbc.update("update orders set shipped_by_account_id=null, shipped_by_username=? where id=?",
                IMPORTER, adminShipped.order()));
    }

    @Test void shipmentActorPredicates() {
        assertThat(Order.isValidShipmentActor(7L, "admin")).isTrue();
        assertThat(Order.isValidShipmentActor(null, IMPORTER)).isTrue();
        assertThat(Order.isValidShipmentActor(null, "admin")).isFalse();
        assertThat(Order.isValidShipmentActor(0L, IMPORTER)).isFalse();
        assertThat(Order.isValidShipmentActor(null, Order.WAREHOUSE_EXPORT_PREPARER)).isFalse();
        assertThat(Order.isWarehouseShipmentImportActor(7L, IMPORTER)).isFalse();
    }

    // ---- fixtures ----

    record Fixture(long order, long payment, String provider) {}

    private static final AtomicInteger DAYS_AGO = new AtomicInteger(30000 + new Random().nextInt(20000));

    void assertSystemShipment(Fixture f, String status) {
        assertThat(order(f)).containsEntry("status", status);
        assertSystemShipmentRow(f);
        assertThat(jdbc.queryForMap("select changed_by_type, changed_by_account_id, changed_by_username from order_status_histories "
                + "where order_id=? and to_status='SHIPPED'", f.order()))
                .containsEntry("changed_by_type", "SYSTEM").containsEntry("changed_by_account_id", null)
                .containsEntry("changed_by_username", IMPORTER);
    }

    void assertSystemShipmentRow(Fixture f) {
        assertThat(order(f)).containsEntry("shipped_by_account_id", null).containsEntry("shipped_by_username", IMPORTER);
        assertThat(order(f).get("shipped_at")).isNotNull();
    }

    Fixture exported(boolean card) {
        var date = nextBusinessDate();
        var f = order(date, card);
        if (!card) jdbc.update("update orders set status='PAID', paid_at=ordered_at where id=?", f.order());
        assertThat(exports.export(batches.openOrGet(date).id(), f.order()).isExported()).isTrue();
        return f;
    }

    void prepareByAdministrator(long order) {
        jdbc.update("update orders set shipping_preparation_started_at=change_deadline_at+interval '10 minutes',"
                + "shipping_preparation_started_by_account_id=7,shipping_preparation_started_by_username='first' where id=?", order);
    }

    CaptureResult captured(Fixture f) { return new CaptureResult(CaptureResultStatus.CAPTURED, f.provider(), null, null); }

    Map<String, Object> order(Fixture f) { return jdbc.queryForMap("select * from orders where id=?", f.order()); }

    Map<String, Object> captureRow(Fixture f) {
        return jdbc.queryForMap("select * from payment_transactions where payment_id=? and transaction_type='CAPTURE'", f.payment());
    }

    int captures(Fixture f) {
        return jdbc.queryForObject("select count(*) from payment_transactions where payment_id=? and transaction_type='CAPTURE'",
                Integer.class, f.payment());
    }

    int shippedHistories(Fixture f) {
        return jdbc.queryForObject("select count(*) from order_status_histories where order_id=? and to_status='SHIPPED'",
                Integer.class, f.order());
    }

    void authenticate(long id, String username) {
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        var principal = new com.example.ecsite.security.AdminUserDetails(id, username, "password", true, authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, "password", authorities));
    }

    <T> T tx(Supplier<T> body) { return new TransactionTemplate(manager).execute(s -> body.get()); }

    LocalDate nextBusinessDate() {
        return jdbc.queryForObject("select (timezone('Asia/Tokyo',clock_timestamp()))::date - ?", LocalDate.class,
                DAYS_AGO.addAndGet(3));
    }

    /** ORDERED order whose deadline is 14:00 of {@code businessDate}; a card order has an AUTHORIZED PAY.JP payment. */
    Fixture order(LocalDate businessDate, boolean card) {
        Fixture f = tx(() -> {
            var now = orders.findCurrentTimeInTokyo(); String key = UUID.randomUUID().toString();
            var user = new User(); user.setUsername("f132-" + key); user.setPassword("unused"); user.setEnabled(true);
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
            long paymentId = 0; String provider = null;
            if (card) {
                var payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, total, now);
                payment.setProviderPaymentId("pfw_" + key, now); payment.markAuthorized(now); payments.saveAndFlush(payment);
                var auth = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE, total, 0, key, now,
                        PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
                auth.markSucceeded(payment.getProviderPaymentId(), now); paymentTransactions.saveAndFlush(auth);
                paymentId = payment.getId(); provider = payment.getProviderPaymentId();
            }
            return new Fixture(order.getId(), paymentId, provider);
        });
        LocalDateTime deadline = businessDate.atTime(14, 0);
        jdbc.update("update orders set ordered_at=?, change_deadline_at=? where id=?", deadline.minusHours(2), deadline, f.order());
        return f;
    }

    static void rejects(String sqlState, ThrowingCallable call) {
        assertThatThrownBy(call).satisfies(e -> {
            Throwable t = e;
            while (t != null && !(t instanceof java.sql.SQLException)) t = t.getCause();
            assertThat(t).as("SQLException in %s", e).isNotNull();
            assertThat(((java.sql.SQLException) t).getSQLState()).as("%s", t).isEqualTo(sqlState);
        });
    }
}
