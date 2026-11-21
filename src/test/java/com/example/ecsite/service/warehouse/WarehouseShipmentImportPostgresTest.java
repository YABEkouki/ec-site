package com.example.ecsite.service.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
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
import com.example.ecsite.service.OrderStatusHistoryService;
import com.example.ecsite.service.payment.PaymentReconciliationItemService;

/**
 * Feature132-B: upload of the shipped CSV (every row judged and recorded) and the background worker that ships
 * the PENDING rows as the system. Each test owns its orders, batches and file content; worker passes only see
 * lines of their own import because every pass is run against freshly claimed lines of this test's file.
 */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false",
        "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false",
        "app.warehouse-shipment-import.enabled=false"})
@com.example.ecsite.support.CaptureConnectionTest
class WarehouseShipmentImportPostgresTest {
    static final String H = WarehouseShipmentCsvParser.HEADER;

    @Autowired WarehouseShipmentImportService imports;
    @Autowired WarehouseShipmentImportQueries queries;
    @Autowired WarehouseShipmentImportWorker worker;
    @Autowired OrderShipmentTrackingReader tracking;
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

    @BeforeEach void admin() {
        // Lines of earlier tests must never be picked up by this test's worker passes.
        jdbc.update("update warehouse_shipment_import_lines set state='REJECTED', reason='RETRY_LIMIT' where state='PENDING'");
        authenticate(5, "uploader");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    // ---- upload ----

    @Test void everyRowIsJudgedAndRecordedWhenTheFileIsUploaded() {
        var date = nextBusinessDate();
        var card = order(date, true); var paid = order(date, false); paid(paid);
        var shipped = order(date, false); paid(shipped);
        var notExported = order(date, true);
        long batch = batches.openOrGet(date).id();
        for (var f : List.of(card, paid, shipped)) assertThat(exports.export(batch, f.order()).isExported()).isTrue();
        batches.seal(batch);
        jdbc.update(SHIP_AS_ADMIN, shipped.order());
        // (An exported order cannot be cancelled any more - V86 - so ORDER_NOT_SHIPPABLE is not reachable here.)
        var openDate = nextBusinessDate(); var unsealed = order(openDate, true);
        long open = batches.openOrGet(openDate).id(); exports.export(open, unsealed.order());

        String future = LocalDate.now().plusDays(30).toString();
        var accepted = imports.upload("C:\\warehouse\\shipped-1.csv", csv(
                row(batch, card.order(), "2026-11-17", "ヤマト運輸", "1234-5678"),
                row(batch, paid.order(), "2026-11-17", "佐川急便", "AB-1"),
                row(batch, shipped.order(), "2026-11-17", "日本郵便", "9"),
                row(batch, notExported.order(), "2026-11-17", "ヤマト運輸", "1"),
                row(open, unsealed.order(), "2026-11-17", "ヤマト運輸", "1"),
                row(batch, card.order(), "2026-11-17", "ヤマト運輸", "2"),
                "x," + card.order() + ",2026-11-17,a,b",
                row(batch, card.order(), "2026/11/17", "a", "b"),
                row(batch, card.order(), future, "a", "b"),
                row(batch, card.order(), "2026-11-17", " ", "b"),
                row(batch, card.order(), "2026-11-17", "a", "b c"),
                "1,2,3"));

        assertThat(accepted.duplicate()).isFalse();
        var found = queries.find(accepted.importId()).orElseThrow();
        assertThat(found.fileName()).isEqualTo("shipped-1.csv");
        assertThat(found.lineCount()).isEqualTo(12);
        assertThat(found.uploadedByAccountId()).isEqualTo(5L);
        var lines = queries.lines(accepted.importId());
        assertThat(lines).extracting(l -> l.state() + (l.reason() == null ? "" : ":" + l.reason())).containsExactly(
                "PENDING", "PENDING", "ALREADY_SHIPPED", "REJECTED:NOT_EXPORTED",
                "REJECTED:BATCH_NOT_SEALED", "REJECTED:DUPLICATE_IN_FILE", "REJECTED:INVALID_FORMAT",
                "REJECTED:INVALID_DATE", "REJECTED:INVALID_DATE", "REJECTED:INVALID_CARRIER",
                "REJECTED:INVALID_TRACKING", "REJECTED:INVALID_FORMAT");
        assertThat(lines.getFirst()).satisfies(l -> {
            assertThat(l.lineNo()).isEqualTo(2);
            assertThat(l.carrier()).isEqualTo("ヤマト運輸");
            assertThat(l.trackingNumber()).isEqualTo("1234-5678");
            assertThat(l.csvShippedOn()).isEqualTo(LocalDate.of(2026, 11, 17));
            assertThat(l.processedAt()).isNull();
        });
        assertThat(lines.get(2).processedAt()).isNotNull();
        assertThat(lines.get(6).orderId()).isNull();
        assertThat(lines.get(6).lineText()).startsWith("x,");
        assertThat(queries.list(60)).filteredOn(l -> l.importRecord().id() == accepted.importId()).singleElement()
                .satisfies(l -> {
                    assertThat(l.pending()).isEqualTo(2);
                    assertThat(l.alreadyShipped()).isEqualTo(1);
                    assertThat(l.actionRequired()).isEqualTo(9);
                });

        // Nothing is shipped by the upload itself.
        assertThat(status(card)).isEqualTo("ORDERED");
        verifyNoInteractions(gateway);
    }

    @Test void theSameFileIsImportedOnceAndAnOrderHasOneActiveLine() {
        var f = sealedExport(true);
        byte[] content = csv(row(f.batch(), f.order(), "2026-11-17", "ヤマト運輸", "1"));
        var first = imports.upload("a.csv", content);
        var again = imports.upload("b.csv", content);
        assertThat(again.duplicate()).isTrue();
        assertThat(again.importId()).isEqualTo(first.importId());

        var other = imports.upload("c.csv", csv(row(f.batch(), f.order(), "2026-11-17", "佐川急便", "2")));
        assertThat(queries.lines(other.importId())).singleElement()
                .satisfies(l -> assertThat(l.reason()).isEqualTo(WarehouseShipmentImportReason.ALREADY_IMPORTED));
    }

    @Test void uploadingRequiresAnAdministratorAndAReadableFile() {
        var f = sealedExport(true);
        assertThatThrownBy(() -> imports.upload("a.csv", "注文番号\n1\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WarehouseShipmentCsvParser.InvalidFileException.class);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> imports.upload("a.csv", csv(row(f.batch(), f.order(), "2026-11-17", "a", "1"))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_shipment_import_lines where order_id=?", Integer.class, f.order()))
                .isZero();
    }

    // ---- worker ----

    @Test void theWorkerShipsCardAndPaidOrdersAsTheSystem() {
        var card = sealedExport(true); var paid = sealedExport(false);
        when(gateway.capture(any())).thenReturn(new CaptureResult(CaptureResultStatus.CAPTURED, card.provider(), null, null));
        long id = upload(row(card.batch(), card.order(), "2026-11-17", "ヤマト運輸", "1"),
                row(paid.batch(), paid.order(), "2026-11-17", "佐川急便", "2"));

        var result = worker.runPass();

        assertThat(result.processed()).isEqualTo(2);
        assertThat(states(id)).containsExactly("SHIPPED", "SHIPPED");
        for (var f : List.of(card, paid)) {
            assertThat(jdbc.queryForMap("select status, shipped_by_account_id, shipped_by_username from orders where id=?", f.order()))
                    .containsEntry("status", "SHIPPED").containsEntry("shipped_by_account_id", null)
                    .containsEntry("shipped_by_username", Order.WAREHOUSE_SHIPMENT_IMPORTER);
        }
        assertThat(queries.lines(id)).allSatisfy(l -> {
            assertThat(l.attempts()).isEqualTo(1);
            assertThat(l.processedAt()).isNotNull();
        });
        // Feature132-C: the shipping line's carrier and tracking number are the order's tracking.
        assertThat(tracking.find(card.order())).hasValueSatisfying(t -> {
            assertThat(t.carrier()).isEqualTo("ヤマト運輸");
            assertThat(t.trackingNumber()).isEqualTo("1");
            assertThat(t.csvShippedOn()).isEqualTo(LocalDate.of(2026, 11, 17));
            assertThat(t.importId()).isEqualTo(id);
            assertThat(t.lineState()).isEqualTo(WarehouseShipmentImportLineState.SHIPPED);
        });
        assertThat(tracking.find(paid.order()).map(OrderShipmentTrackingReader.Tracking::trackingNumber)).contains("2");
        // A later pass has nothing to do.
        assertThat(worker.runPass().processed()).isZero();
        verify(gateway, times(1)).capture(any());
    }

    @Test void anUnknownCaptureWaitsAndSettlesAfterReconciliation() {
        var f = sealedExport(true);
        when(gateway.capture(any())).thenThrow(new PaymentGatewayException("timeout", new RuntimeException()));
        long id = upload(row(f.batch(), f.order(), "2026-11-17", "ヤマト運輸", "1"));

        worker.runPass();
        assertThat(states(id)).containsExactly("CAPTURE_UNCONFIRMED");
        assertThat(tracking.find(f.order())).isPresent();
        worker.runPass();
        assertThat(states(id)).containsExactly("CAPTURE_UNCONFIRMED");

        when(gateway.retrievePaymentFlow(f.provider())).thenReturn(new PaymentFlowState(f.provider(), PaymentFlowStatus.SUCCEEDED, null, null));
        long capture = jdbc.queryForObject("select id from payment_transactions where payment_id=? and transaction_type='CAPTURE'", Long.class, f.payment());
        reconciliation.reconcile(capture);
        assertThat(worker.runPass().settled()).isEqualTo(1);
        assertThat(states(id)).containsExactly("SHIPPED");
        verify(gateway, times(1)).capture(any());
    }

    @Test void aFailedCaptureIsRecordedForTheAdministrator() {
        var f = sealedExport(true);
        when(gateway.capture(any())).thenReturn(new CaptureResult(CaptureResultStatus.FAILED, f.provider(), "card_declined", "declined"));
        long id = upload(row(f.batch(), f.order(), "2026-11-17", "ヤマト運輸", "1"));
        worker.runPass();
        assertThat(states(id)).containsExactly("CAPTURE_FAILED");
        assertThat(queries.lines(id).getFirst().state().actionRequired()).isTrue();
    }

    @Test void aRefusedShipmentIsRetriedAndThenRejected() {
        var f = sealedExport(true);
        long id = upload(row(f.batch(), f.order(), "2026-11-17", "ヤマト運輸", "1"));
        // The exported payment no longer matches: the shipment operation refuses before any PAY.JP call.
        jdbc.update("update payments set status='CANCELLED' where id=?", f.payment());

        worker.runPass(); worker.runPass();
        assertThat(states(id)).containsExactly("PENDING");
        worker.runPass();
        assertThat(states(id)).containsExactly("REJECTED");
        assertThat(queries.lines(id).getFirst()).satisfies(l -> {
            assertThat(l.reason()).isEqualTo(WarehouseShipmentImportReason.SHIPMENT_REFUSED);
            assertThat(l.attempts()).isEqualTo(3);
        });
        assertThat(status(f)).isEqualTo("ORDERED");
        verify(gateway, never()).capture(any());
    }

    @Test void anOrderShippedMeanwhileIsAlreadyShippedAndAnInterruptedShipmentIsCompleted() {
        var byAdmin = sealedExport(false); var interrupted = sealedExport(false);
        long id = upload(row(byAdmin.batch(), byAdmin.order(), "2026-11-17", "a", "1"),
                row(interrupted.batch(), interrupted.order(), "2026-11-17", "a", "2"));
        jdbc.update(SHIP_AS_ADMIN, byAdmin.order());
        // A previous pass shipped the order but stopped before recording the line.
        jdbc.update("update orders set status='SHIPPED', shipped_at=timezone('Asia/Tokyo',clock_timestamp()),"
                + "shipped_by_account_id=null, shipped_by_username=? where id=?", Order.WAREHOUSE_SHIPMENT_IMPORTER, interrupted.order());
        worker.runPass();
        assertThat(states(id)).containsExactly("ALREADY_SHIPPED", "SHIPPED");
        // An order shipped by an administrator shows no warehouse tracking, even if the CSV listed it.
        assertThat(tracking.find(byAdmin.order())).isEmpty();
        assertThat(tracking.find(interrupted.order())).isPresent();
    }

    @Test void theDatabaseGuardsImportLines() {
        var f = sealedExport(true);
        long id = upload(row(f.batch(), f.order(), "2026-11-17", "ヤマト運輸", "1"));
        long line = jdbc.queryForObject("select id from warehouse_shipment_import_lines where import_id=?", Long.class, id);
        rejects("55000", () -> jdbc.update("update warehouse_shipment_import_lines set tracking_number='X' where id=?", line));
        rejects("55000", () -> jdbc.update("delete from warehouse_shipment_import_lines where id=?", line));
        rejects("55000", () -> jdbc.update("update warehouse_shipment_imports set file_name='x' where id=?", id));
        // SHIPPED needs an order shipped by the import; ALREADY_SHIPPED needs a shipped order.
        rejects("23514", () -> jdbc.update("update warehouse_shipment_import_lines set state='SHIPPED' where id=?", line));
        rejects("23514", () -> jdbc.update("update warehouse_shipment_import_lines set state='ALREADY_SHIPPED' where id=?", line));
        jdbc.update("update warehouse_shipment_import_lines set state='REJECTED', reason='RETRY_LIMIT' where id=?", line);
        rejects("55000", () -> jdbc.update("update warehouse_shipment_import_lines set state='PENDING', reason=null where id=?", line));
        // A PENDING line must name an order of a sealed batch; a second active line per order is refused.
        var other = order(nextBusinessDate(), true);
        rejects("23514", () -> jdbc.update("insert into warehouse_shipment_import_lines(import_id,line_no,line_text,batch_id,order_id,"
                + "csv_shipped_on,carrier,tracking_number,state) values (?,99,'x',?,?,current_date,'a','1','PENDING')",
                id, f.batch(), other.order()));
        jdbc.update("insert into warehouse_shipment_import_lines(import_id,line_no,line_text,batch_id,order_id,"
                + "csv_shipped_on,carrier,tracking_number,state) values (?,98,'x',?,?,current_date,'a','1','PENDING')", id, f.batch(), f.order());
        rejects("23505", () -> jdbc.update("insert into warehouse_shipment_import_lines(import_id,line_no,line_text,batch_id,order_id,"
                + "csv_shipped_on,carrier,tracking_number,state) values (?,97,'x',?,?,current_date,'a','1','PENDING')", id, f.batch(), f.order()));
    }

    // ---- fixtures ----

    static final String SHIP_AS_ADMIN = "update orders set status='SHIPPED', shipped_at=timezone('Asia/Tokyo',clock_timestamp()),"
            + "shipped_by_account_id=7, shipped_by_username='admin' where id=?";

    record Fixture(long order, long payment, String provider, long batch) {}

    private static final AtomicInteger DAYS_AGO = new AtomicInteger(60000 + new Random().nextInt(20000));

    long upload(String... rows) { return imports.upload("shipped.csv", csv(rows)).importId(); }

    List<String> states(long importId) {
        return jdbc.queryForList("select state from warehouse_shipment_import_lines where import_id=? order by line_no", String.class, importId);
    }

    String status(Fixture f) { return jdbc.queryForObject("select status from orders where id=?", String.class, f.order()); }

    /** Rows name this test's own orders, so the SHA-256 never collides with another test's file. */
    static byte[] csv(String... rows) {
        var s = new StringBuilder(H).append("\r\n");
        for (var r : rows) s.append(r).append("\r\n");
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String row(long batch, long order, String date, String carrier, String tracking) {
        return batch + "," + order + "," + date + "," + carrier + "," + tracking;
    }

    void paid(Fixture f) { jdbc.update("update orders set status='PAID', paid_at=ordered_at where id=?", f.order()); }

    Fixture sealedExport(boolean card) {
        var date = nextBusinessDate();
        var f = order(date, card);
        if (!card) paid(f);
        long batch = batches.openOrGet(date).id();
        assertThat(exports.export(batch, f.order()).isExported()).isTrue();
        batches.seal(batch);
        return new Fixture(f.order(), f.payment(), f.provider(), batch);
    }

    void authenticate(long id, String username) {
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        var principal = new com.example.ecsite.security.AdminUserDetails(id, username, "password", true, authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, "password", authorities));
    }

    <T> T tx(Supplier<T> body) { return new TransactionTemplate(manager).execute(s -> body.get()); }

    LocalDate nextBusinessDate() {
        return jdbc.queryForObject("select (timezone('Asia/Tokyo',clock_timestamp()))::date - ?", LocalDate.class, DAYS_AGO.addAndGet(3));
    }

    Fixture order(LocalDate businessDate, boolean card) {
        Fixture f = tx(() -> {
            var now = orders.findCurrentTimeInTokyo(); String key = UUID.randomUUID().toString();
            var user = new User(); user.setUsername("f132b-" + key); user.setPassword("unused"); user.setEnabled(true);
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
            // As checkout does (Feature137: the warehouse file rows must add up to the total and tax, V101).
            order.applyAmount(new com.example.ecsite.service.pricing.OrderAmount(price * quantity, java.util.List.of(), 550,
                    java.math.BigDecimal.valueOf(total).multiply(tax.getTaxRate()).divide(
                            java.math.BigDecimal.valueOf(100).add(tax.getTaxRate()), 0, java.math.RoundingMode.DOWN).intValueExact(),
                    total));
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
            return new Fixture(order.getId(), paymentId, provider, 0);
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
