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
    @Autowired com.example.ecsite.service.orderchange.OrderChangeFinalDecisionService finalDecisions;
    @Autowired WarehouseFileSource fileSource;
    @Autowired WarehouseFileOutputStore fileOutputs;
    @Autowired WarehouseExportShipmentGuard shipmentGuard;
    @Autowired WarehouseExportHoldStore holdStore;
    @Autowired com.example.ecsite.service.payment.PaymentAuthorizationExpiryStore expiries;
    @Autowired com.example.ecsite.service.OrderShippingPreparationService preparations;
    @Autowired com.example.ecsite.service.payment.PaymentCaptureStartService captureStarts;
    @Autowired com.example.ecsite.service.payment.AdminAuthorizationExpiryQueries expiryQueries;
    @Autowired com.example.ecsite.service.OrderService orderService;
    @Autowired com.example.ecsite.service.payment.AdminPaymentCancellationService adminCancellation;
    @Autowired com.example.ecsite.service.payment.ExpiredAuthorizationCancellationService expiredCancellation;
    @Autowired com.example.ecsite.service.payment.AuthorizationExpiryRefreshService.Transactions refreshTransactions;
    @Autowired org.springframework.context.ApplicationContext context;

    // ---- Feature135-A1: authorization expiry ----

    @Test void everyAuthorizationGetsAConservativeEstimateReplacedOnceByTheProviderValue() {
        var f = card(nextBusinessDate());
        var row = expiryRow(f.payment());
        assertThat(row).containsEntry("source", "ESTIMATED").containsEntry("provider_recorded_at", null);
        assertThat(row.get("estimated_expires_at")).isEqualTo(row.get("expires_at"));
        assertThat(jdbc.queryForObject("select e.estimated_expires_at = p.created_at + interval '5 days' from payment_authorization_expiries e"
                + " join payments p on p.id=e.payment_id where e.payment_id=?", Boolean.class, f.payment())).isTrue();

        var provider = java.time.Instant.now().plus(java.time.Duration.ofDays(7)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        tx(() -> { expiries.recordProvider(f.payment(), provider); return null; });
        var stored = expiries.find(f.payment()).orElseThrow();
        assertThat(stored.source()).isEqualTo(com.example.ecsite.service.payment.PaymentAuthorizationExpiryStore.Source.PROVIDER);
        assertThat(stored.expiresAt()).isEqualTo(LocalDateTime.ofInstant(provider, java.time.ZoneId.of("Asia/Tokyo")));
        assertThat(expiryRow(f.payment()).get("provider_recorded_at")).isNotNull();
        // Exactly once: a later value is ignored and the database refuses any other change or deletion.
        tx(() -> { expiries.recordProvider(f.payment(), provider.plusSeconds(3600)); return null; });
        assertThat(expiries.find(f.payment()).orElseThrow().expiresAt()).isEqualTo(stored.expiresAt());
        rejects("55000", () -> jdbc.update("update payment_authorization_expiries set expires_at=expires_at+interval '1 day' where payment_id=?", f.payment()));
        rejects("55000", () -> jdbc.update("update payment_authorization_expiries set source='ESTIMATED', provider_recorded_at=null,"
                + " expires_at=estimated_expires_at where payment_id=?", f.payment()));
        rejects("55000", () -> jdbc.update("delete from payment_authorization_expiries where payment_id=?", f.payment()));
        rejects("55000", () -> jdbc.execute("truncate payment_authorization_expiries"));

        // Only AUTHORIZED payments get a row, and callers cannot choose its values.
        var plain = order(nextBusinessDate(), false);
        long failed = tx(() -> {
            var now = orders.findCurrentTimeInTokyo(); var order = orders.findById(plain.order()).orElseThrow();
            var p = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 750, now);
            p.setProviderPaymentId("pfw_failed_" + plain.order(), now); p.markFailed(now); payments.saveAndFlush(p);
            return p.getId();
        });
        assertThat(expiries.find(failed)).isEmpty();
        rejects("23514", () -> jdbc.update("insert into payment_authorization_expiries(payment_id,authorization_base_at,estimated_expires_at,"
                + "expires_at,source,created_at) values (?,now(),now()+interval '5 days',now()+interval '5 days','ESTIMATED',now())", failed));

        // A value outside the plausible range keeps the estimate instead of failing the authorization.
        var odd = card(nextBusinessDate());
        tx(() -> { expiries.recordProvider(odd.payment(), java.time.Instant.parse("2000-01-01T00:00:00Z")); return null; });
        assertThat(expiryRow(odd.payment())).containsEntry("source", "ESTIMATED");
    }

    @Test void anExpiringAuthorizationIsHeldFromTheExportAndTheDatabaseRefusesIt() {
        var date = nextBusinessDate(); long batch = batch(date);
        var soon = card(date);
        providerExpiry(soon.payment(), "interval '23 hours'");
        assertHeld(soon, batch, Reason.AUTHORIZATION_EXPIRING);
        assertThat(jdbc.queryForList("select reason from warehouse_export_holds where order_id=?", String.class, soon.order()))
                .containsExactly("AUTHORIZATION_EXPIRING");
        assertThat(WarehouseExportHoldStore.label(Reason.AUTHORIZATION_EXPIRING)).isNotBlank();
        assertThat(WarehouseExportHoldStore.guidance(Reason.AUTHORIZATION_EXPIRING)).isNotBlank();

        // The runner records the hold; an estimate already in the past is held the same way.
        var expired = card(date, 10);
        runner(date).run(date);
        assertThat(holdStore.pending(soon.order())).singleElement().satisfies(h -> assertThat(h.reason()).isEqualTo(Reason.AUTHORIZATION_EXPIRING));
        assertThat(holdStore.pending(expired.order())).singleElement().satisfies(h -> assertThat(h.reason()).isEqualTo(Reason.AUTHORIZATION_EXPIRING));
        assertUnprepared(soon.order()); assertUnprepared(expired.order());

        // Database: the preparation itself is refused ...
        assertThatThrownBy(() -> tx(() -> jdbc.update("update orders set shipping_preparation_started_at=change_deadline_at+interval '10 minutes',"
                + "shipping_preparation_started_by_account_id=7,shipping_preparation_started_by_username='first' where id=?", expired.order())))
                .hasMessageContaining("expires too soon for shipping preparation");
        // ... and so is the export line when the expiry changes after the preparation in the same transaction.
        var late = card(date.minusDays(1));
        long lateBatch = batch(date.minusDays(1));
        assertThatThrownBy(() -> tx(() -> {
            jdbc.update("update orders set shipping_preparation_started_at=timezone('Asia/Tokyo',clock_timestamp()),"
                    + "shipping_preparation_started_by_username='WAREHOUSE_EXPORT' where id=?", late.order());
            providerExpiry(late.payment(), "interval '1 hour'");
            return jdbc.update("insert into warehouse_export_lines(batch_id,order_id) values (?,?)", lateBatch, late.order());
        })).hasMessageContaining("expires too soon for the warehouse export");
        assertUnprepared(late.order());
    }

    @Test void administratorPreparationAndCaptureStartRespectTheExpiry() {
        var date = nextBusinessDate();
        authenticate(41, "expiry-admin");
        com.example.ecsite.support.EmergencyShippingFixtures.ensurePermittedAdmin(jdbc, 41L, "expiry-admin");
        var soon = card(date);
        providerExpiry(soon.payment(), "interval '23 hours'");
        assertThatThrownBy(() -> com.example.ecsite.support.EmergencyShippingFixtures.start(preparations, jdbc, soon.order()))
                .isInstanceOf(com.example.ecsite.service.payment.AuthorizationExpiryException.class)
                .hasMessageContaining("24時間未満");
        assertUnprepared(soon.order());

        // Prepared while valid (estimate: created 3 days ago + 5 days), then the provider value shows it has expired.
        var prepared = card(date, 3);
        assertThat(com.example.ecsite.support.EmergencyShippingFixtures.start(preparations, jdbc, prepared.order()).status())
                .isEqualTo(com.example.ecsite.service.ShippingPreparationStartResult.Status.STARTED);
        jdbc.update("update payment_authorization_expiries set source='PROVIDER', expires_at=authorization_base_at+interval '1 hour'"
                + " where payment_id=?", prepared.payment());
        assertThatThrownBy(() -> captureStarts.start(prepared.order(), 41L, "expiry-admin", null))
                .isInstanceOf(com.example.ecsite.service.payment.AuthorizationExpiryException.class)
                .hasMessageContaining("有効期限が切れている");
        assertThat(orderRow(prepared.order())).containsEntry("status", "ORDERED").containsEntry("shipment_capture_payment_id", null);
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where payment_id=? and transaction_type='CAPTURE'",
                Integer.class, prepared.payment())).isZero();
        // The database refuses the card shipment as well.
        assertThatThrownBy(() -> jdbc.update("update orders set status='SHIPPED', shipped_at=timezone('Asia/Tokyo',clock_timestamp()),"
                + "shipped_by_account_id=9, shipped_by_username='shipper', shipment_capture_payment_id=? where id=?",
                prepared.payment(), prepared.order())).hasMessageContaining("shipment capture cannot start");
    }

    @Test void administratorScreensListAndFilterAuthorizationExpiries() {
        var date = nextBusinessDate();
        var valid = card(date); var soon = card(date); var expired = card(date, 10); var paid = order(date, false);
        providerExpiry(soon.payment(), "interval '5 hours'");
        jdbc.update("update orders set status='PAID', paid_at=ordered_at where id=?", paid.order());

        var badges = expiryQueries.badges(List.of(valid.order(), soon.order(), expired.order(), paid.order()));
        assertThat(badges).doesNotContainKey(paid.order());
        assertThat(badges.get(valid.order()).level()).isEqualTo(com.example.ecsite.service.payment.AdminAuthorizationExpiryQueries.Level.VALID);
        assertThat(badges.get(valid.order()).estimated()).isTrue();
        assertThat(badges.get(soon.order()).level()).isEqualTo(com.example.ecsite.service.payment.AdminAuthorizationExpiryQueries.Level.SOON);
        assertThat(badges.get(soon.order()).estimated()).isFalse();
        assertThat(badges.get(soon.order()).label()).matches("与信期限まで[45]時間");
        assertThat(badges.get(expired.order()).level()).isEqualTo(com.example.ecsite.service.payment.AdminAuthorizationExpiryQueries.Level.EXPIRED);
        assertThat(expiryQueries.badges(List.of())).isEmpty();

        assertThat(expiryQueries.orderIds(com.example.ecsite.form.AdminAuthorizationExpiryFilter.ATTENTION))
                .contains(soon.order(), expired.order()).doesNotContain(valid.order(), paid.order());
        assertThat(expiryQueries.orderIds(com.example.ecsite.form.AdminAuthorizationExpiryFilter.EXPIRED))
                .contains(expired.order()).doesNotContain(soon.order(), valid.order());

        // The order search applies the filter together with its other conditions.
        var form = new com.example.ecsite.form.AdminOrderSearchForm();
        form.setFrom(date.minusDays(1)); form.setTo(date.plusDays(1));
        assertThat(orderService.searchOrders(form, 1L, 0, 100).getContent()).extracting(Order::getId)
                .contains(valid.order(), soon.order(), expired.order(), paid.order());
        form.setAuthorizationExpiry(com.example.ecsite.form.AdminAuthorizationExpiryFilter.ATTENTION);
        assertThat(orderService.searchOrders(form, 1L, 0, 100).getContent()).extracting(Order::getId)
                .contains(soon.order(), expired.order()).doesNotContain(valid.order(), paid.order());
        form.setAuthorizationExpiry(com.example.ecsite.form.AdminAuthorizationExpiryFilter.EXPIRED);
        var page = orderService.searchOrders(form, 1L, 0, 100);
        assertThat(page.getContent()).extracting(Order::getId).contains(expired.order()).doesNotContain(soon.order());
        assertThat(page.getTotalElements()).isEqualTo(page.getContent().size());
        assertThat(orderService.searchAllOrders(form, 1L)).extracting(Order::getId).contains(expired.order()).doesNotContain(soon.order());

        // Detail: every AUTHORIZED payment and the order's open discrepancies.
        jdbc.update("""
                insert into payment_discrepancies(payment_id,local_status,provider_status,status,first_detected_at,last_detected_at,
                    detection_count,created_at,updated_at,handling_status,version)
                values (?,'AUTHORIZED','CANCELED','OPEN',now(),now(),1,now(),now(),'UNCONFIRMED',0)
                """, expired.payment());
        var detail = expiryQueries.forOrder(expired.order());
        assertThat(detail.payments()).singleElement().satisfies(p -> {
            assertThat(p.paymentId()).isEqualTo(expired.payment());
            assertThat(p.badge().attention()).isTrue();
            assertThat(p.sourceLabel()).contains("推定");
        });
        assertThat(detail.openDiscrepancies()).singleElement().satisfies(d -> assertThat(d.providerStatus()).isEqualTo("CANCELED"));
        assertThat(expiryQueries.forOrder(paid.order()).isEmpty()).isTrue();
    }

    // ---- Feature135-B1: cancelling an order whose authorization has expired ----

    String flow(Fixture f) { return jdbc.queryForObject("select provider_payment_id from payments where id=?", String.class, f.payment()); }

    void stubFlow(Fixture f, com.example.ecsite.payment.PaymentFlowStatus status) {
        org.mockito.Mockito.when(gateway.retrievePaymentFlow(flow(f)))
                .thenReturn(new com.example.ecsite.payment.PaymentFlowState(flow(f), status, null, null));
    }

    void stubCancelPost(Fixture f) {
        org.mockito.Mockito.when(gateway.cancelAuthorization(org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.example.ecsite.payment.CancellationResult(com.example.ecsite.payment.CancellationResultStatus.CANCELLED, flow(f)));
    }

    void assertCancelled(Fixture f, String actorType) {
        assertThat(orderRow(f.order())).containsEntry("status", "CANCELLED");
        assertThat(orderRow(f.order()).get("cancelled_at")).isNotNull();
        assertThat(jdbc.queryForObject("select status from payments where id=?", String.class, f.payment())).isEqualTo("CANCELLED");
        var cancel = jdbc.queryForMap("select * from payment_transactions where payment_id=? and transaction_type='CANCEL'", f.payment());
        assertThat(cancel).containsEntry("status", "SUCCEEDED").containsEntry("purpose", "ORDER_CANCELLATION")
                .containsEntry("provider_transaction_id", flow(f)).containsEntry("initiator_type", actorType);
        assertThat(jdbc.queryForObject("select count(*) from order_status_histories where order_id=? and to_status='CANCELLED' and changed_by_type=?",
                Integer.class, f.order(), actorType)).isEqualTo(1);
    }

    int expiredObservations(Fixture f) {
        return jdbc.queryForObject("select count(*) from payment_expired_cancellation_observations where payment_id=?", Integer.class, f.payment());
    }

    @Test void anAdministratorCancelsAnExpiredAuthorizationAfterTheDeadlineWithoutAPost() {
        var f = card(nextBusinessDate(), 10);
        authenticate(51, "cancel-admin");
        // The regular cancellation is closed after the change deadline.
        assertThatThrownBy(() -> adminCancellation.cancel(f.order(), 51L, "cancel-admin", null))
                .hasMessageContaining("変更受付は終了");
        assertThat(expiredCancellation.available(f.order())).isTrue();
        // Feature135-C1: counted on the dashboard and in the filter while it waits for the administrator.
        assertThat(expiryQueries.orderIds(com.example.ecsite.form.AdminAuthorizationExpiryFilter.EXPIRED)).contains(f.order());
        var waiting = expiryQueries.summary();
        assertThat(waiting.expired()).isEqualTo(expiryQueries.orderIds(com.example.ecsite.form.AdminAuthorizationExpiryFilter.EXPIRED).size());
        assertThat(waiting.attention()).isEqualTo(expiryQueries.orderIds(com.example.ecsite.form.AdminAuthorizationExpiryFilter.ATTENTION).size());
        assertThat(expiryQueries.forOrder(f.order()).expiredCancellation()).isNull();
        stubFlow(f, com.example.ecsite.payment.PaymentFlowStatus.CANCELED);
        int stock = jdbc.queryForObject("select stock from products p join order_items i on i.product_id=p.id where i.order_id=?", Integer.class, f.order());

        var outcome = expiredCancellation.cancel(f.order(), 51L, "cancel-admin", " 期限切れ ");

        assertThat(outcome).isEqualTo(com.example.ecsite.service.payment.ExpiredAuthorizationCancellationService.Outcome.CANCELLED);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).cancelAuthorization(org.mockito.ArgumentMatchers.any());
        assertCancelled(f, "ADMIN");
        assertThat(jdbc.queryForObject("select internal_note from payment_transactions where payment_id=? and transaction_type='CANCEL'",
                String.class, f.payment())).isEqualTo("期限切れ");
        assertThat(jdbc.queryForObject("select stock from products p join order_items i on i.product_id=p.id where i.order_id=?", Integer.class, f.order()))
                .isEqualTo(stock + 2);
        var observed = jdbc.queryForMap("select * from payment_expired_cancellation_observations where payment_id=?", f.payment());
        assertThat(observed).containsEntry("provider_payment_id", flow(f)).containsEntry("expiry_source", "ESTIMATED")
                .containsEntry("observed_status", "canceled");
        assertThat(observed.get("transaction_id")).isEqualTo(jdbc.queryForObject(
                "select id from payment_transactions where payment_id=? and transaction_type='CANCEL'", Long.class, f.payment()));
        rejects("55000", () -> jdbc.update("update payment_expired_cancellation_observations set observed_status='canceled' where payment_id=?", f.payment()));
        rejects("55000", () -> jdbc.update("delete from payment_expired_cancellation_observations where payment_id=?", f.payment()));

        // Idempotent: a repeated request changes nothing.
        org.mockito.Mockito.clearInvocations(gateway);
        assertThat(expiredCancellation.cancel(f.order(), 51L, "cancel-admin", null))
                .isEqualTo(com.example.ecsite.service.payment.ExpiredAuthorizationCancellationService.Outcome.ALREADY_CANCELLED);
        org.mockito.Mockito.verifyNoInteractions(gateway);
        assertThat(expiredObservations(f)).isEqualTo(1);
        assertThat(expiredCancellation.available(f.order())).isFalse();
        // Feature135-C2 (V96): the customer is told why, in the cancellation notification and on the order detail.
        var notice = jdbc.queryForMap("select payload::text as payload from customer_order_notifications where order_id=? and event_type='ORDER_CANCELLED'", f.order());
        assertThat((String) notice.get("payload")).contains("\"authorizationExpired\": true").contains("\"changedByType\": \"ADMIN\"");
        assertThat(expiries.cancelledForExpiredAuthorization(f.order())).isTrue();
        // Feature135-C1: no longer counted; the detail shows why it was cancelled.
        assertThat(expiryQueries.orderIds(com.example.ecsite.form.AdminAuthorizationExpiryFilter.EXPIRED)).doesNotContain(f.order());
        var record = expiryQueries.forOrder(f.order()).expiredCancellation();
        assertThat(record.paymentId()).isEqualTo(f.payment());
        assertThat(record.authorizationExpiresAt()).isEqualTo(((java.sql.Timestamp) observed.get("authorization_expires_at")).toLocalDateTime());
    }

    @Test void anExpiredAuthorizationNotYetCanceledAtPayJpOrUnreadableIsLeftUnchanged() {
        authenticate(52, "cancel-admin");
        var open = card(nextBusinessDate(), 10);
        stubFlow(open, com.example.ecsite.payment.PaymentFlowStatus.REQUIRES_CAPTURE);
        assertThatThrownBy(() -> expiredCancellation.cancel(open.order(), 52L, "cancel-admin", null))
                .isInstanceOf(com.example.ecsite.service.payment.AuthorizationExpiryException.class)
                .hasMessageContaining("まだ与信が取り消されていません");
        var unreadable = card(nextBusinessDate(), 10);
        org.mockito.Mockito.when(gateway.retrievePaymentFlow(flow(unreadable))).thenThrow(new com.example.ecsite.payment.PaymentGatewayException(
                com.example.ecsite.payment.PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN, null, null, true));
        assertThatThrownBy(() -> expiredCancellation.cancel(unreadable.order(), 52L, "cancel-admin", null))
                .isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).cancelAuthorization(org.mockito.ArgumentMatchers.any());
        for (var f : List.of(open, unreadable)) {
            assertThat(orderRow(f.order())).containsEntry("status", "ORDERED");
            assertThat(jdbc.queryForObject("select count(*) from payment_transactions where payment_id=? and transaction_type='CANCEL'",
                    Integer.class, f.payment())).isZero();
            assertThat(expiredObservations(f)).isZero();
        }
    }

    @Test void onlyAnUnpreparedOrderWithAnExpiredAuthorizationIsEligible() {
        authenticate(53, "cancel-admin");
        var valid = card(nextBusinessDate());
        assertThat(expiredCancellation.available(valid.order())).isFalse();
        assertThatThrownBy(() -> expiredCancellation.cancel(valid.order(), 53L, "cancel-admin", null))
                .isInstanceOf(com.example.ecsite.service.payment.AuthorizationExpiryException.class);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).retrievePaymentFlow(org.mockito.ArgumentMatchers.anyString());

        // Prepared while valid, then the provider value shows it has expired: shipping preparation blocks this cancellation.
        var prepared = card(nextBusinessDate(), 3);
        adminPrepare(prepared.order());
        jdbc.update("update payment_authorization_expiries set source='PROVIDER', expires_at=authorization_base_at+interval '1 hour'"
                + " where payment_id=?", prepared.payment());
        assertThat(expiredCancellation.available(prepared.order())).isFalse();
        assertThatThrownBy(() -> expiredCancellation.cancel(prepared.order(), 53L, "cancel-admin", null))
                .hasMessageContaining("発送準備を開始した注文");
        assertThat(orderRow(prepared.order())).containsEntry("status", "ORDERED");
    }

    @Test void theDatabaseRefusesAnyOtherCancellationAfterTheDeadline() {
        var f = card(nextBusinessDate(), 10);
        // A regular ORDER_CANCELLATION created after the change deadline, without the expired-cancellation record.
        assertThatThrownBy(() -> tx(() -> {
            var now = orders.findCurrentTimeInTokyo(); var payment = payments.findById(f.payment()).orElseThrow();
            paymentTransactions.saveAndFlush(new PaymentTransaction(payment, PaymentTransactionType.CANCEL, payment.getAmount(), 0,
                    UUID.randomUUID().toString(), now, PaymentTransactionPurpose.ORDER_CANCELLATION));
            return null;
        })).hasStackTraceContaining("must be an expired-authorization cancellation");
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where payment_id=? and transaction_type='CANCEL'",
                Integer.class, f.payment())).isZero();

        // The audit row itself needs a CANCEL transaction of an expired authorization.
        var valid = card(nextBusinessDate());
        long cancel = tx(() -> {
            var payment = payments.findById(valid.payment()).orElseThrow();
            var t = new PaymentTransaction(payment, PaymentTransactionType.CANCEL, payment.getAmount(), 0,
                    UUID.randomUUID().toString(), payment.getOrder().getChangeDeadlineAt().minusHours(1), PaymentTransactionPurpose.ORDER_CANCELLATION);
            paymentTransactions.saveAndFlush(t);
            return t.getId();
        });
        rejects("23514", () -> jdbc.update("""
                insert into payment_expired_cancellation_observations
                    (transaction_id,payment_id,provider_payment_id,authorization_expires_at,expiry_source,observed_status,observed_at)
                values (?,?,?,now(),'ESTIMATED','canceled',now())""", cancel, valid.payment(), flow(valid)));
    }

    Map<String, Object> expiryRow(long payment) {
        return jdbc.queryForMap("select * from payment_authorization_expiries where payment_id=?", payment);
    }

    /** The one allowed replacement: PROVIDER value at the database clock plus {@code interval}. */
    void providerExpiry(long payment, String interval) {
        assertThat(jdbc.update("update payment_authorization_expiries set source='PROVIDER',"
                + " expires_at=timezone('Asia/Tokyo',clock_timestamp())+" + interval + " where payment_id=?", payment)).isEqualTo(1);
    }

    Fixture card(LocalDate businessDate, int paymentAgeDays) { return order(businessDate, true, paymentAgeDays); }

    // ---- Feature135-B3: replacing ESTIMATED expiries with PAY.JP expired_at ----

    /** A clock the test moves forward, for the in-process retry interval. */
    static final class MovableClock extends java.time.Clock {
        java.time.Instant now = java.time.Instant.now();
        @Override public java.time.ZoneId getZone() { return java.time.ZoneId.of("Asia/Tokyo"); }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return now; }
    }

    com.example.ecsite.service.payment.AuthorizationExpiryRefreshService refresher(java.time.Clock clock) {
        return new com.example.ecsite.service.payment.AuthorizationExpiryRefreshService(refreshTransactions, gateway, clock, 200, java.time.Duration.ofHours(6));
    }

    void stubAuthorization(Fixture f, com.example.ecsite.payment.AuthorizationResultStatus status, java.time.Instant expiresAt) {
        org.mockito.Mockito.when(gateway.retrieveAuthorization(flow(f))).thenAnswer(call -> {
            // The GET is never inside a database transaction.
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new com.example.ecsite.payment.AuthorizationRecovery(
                    new com.example.ecsite.payment.AuthorizationResult(status, flow(f), null, null, expiresAt), null);
        });
    }

    java.time.Instant inDays(long days) { return java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plus(java.time.Duration.ofDays(days)); }

    boolean validNow(Fixture f) {
        return jdbc.queryForObject("select f135_authorization_valid_beyond(?, interval '0 seconds')", Boolean.class, f.payment());
    }

    @Test void estimatedExpiriesAreReplacedByPayJpExpiredAtOnlyForUncapturedFlows() {
        var date = nextBusinessDate();
        var fresh = card(date); var estimatedOut = card(date, 6); var canceled = card(date, 10); var unreadable = card(date, 3); var odd = card(date, 2);
        var freshValue = inDays(7); var extended = inDays(1);
        stubAuthorization(fresh, com.example.ecsite.payment.AuthorizationResultStatus.AUTHORIZED, freshValue);
        stubAuthorization(estimatedOut, com.example.ecsite.payment.AuthorizationResultStatus.AUTHORIZED, extended);
        stubAuthorization(canceled, com.example.ecsite.payment.AuthorizationResultStatus.FAILED, null);
        stubAuthorization(odd, com.example.ecsite.payment.AuthorizationResultStatus.AUTHORIZED, inDays(70));
        org.mockito.Mockito.when(gateway.retrieveAuthorization(flow(unreadable))).thenThrow(new com.example.ecsite.payment.PaymentGatewayException("timeout"));
        assertThat(validNow(estimatedOut)).isFalse();
        var clock = new MovableClock(); var service = refresher(clock);

        service.runPass();
        assertThat(expiryRow(fresh.payment())).containsEntry("source", "PROVIDER")
                .containsEntry("expires_at", java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(freshValue, java.time.ZoneId.of("Asia/Tokyo"))));
        assertThat(expiryRow(fresh.payment()).get("provider_recorded_at")).isNotNull();
        // The estimate had passed, PAY.JP still holds the authorization: valid again from PAY.JP's own value.
        assertThat(expiryRow(estimatedOut.payment())).containsEntry("source", "PROVIDER");
        assertThat(validNow(estimatedOut)).isTrue();
        // Canceled, unreadable and out-of-range answers keep the conservative estimate.
        for (var kept : List.of(canceled, unreadable, odd)) assertThat(expiryRow(kept.payment())).containsEntry("source", "ESTIMATED");
        for (var f : List.of(fresh, estimatedOut, canceled, unreadable, odd)) org.mockito.Mockito.verify(gateway).retrieveAuthorization(flow(f));

        // Next pass: recorded ones are no longer candidates; canceled/out-of-range wait 6 hours; a failed GET is retried.
        service.runPass();
        for (var f : List.of(fresh, estimatedOut, canceled, odd)) org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).retrieveAuthorization(flow(f));
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(2)).retrieveAuthorization(flow(unreadable));
        clock.now = clock.now.plus(java.time.Duration.ofHours(6));
        service.runPass();
        for (var f : List.of(canceled, odd)) org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(2)).retrieveAuthorization(flow(f));
        for (var f : List.of(fresh, estimatedOut)) org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).retrieveAuthorization(flow(f));
    }

    @Test void onlyEstimatedAuthorizationsOfLiveCardOrdersWithin25DaysAreAsked() {
        var date = nextBusinessDate();
        var estimated = card(date, 3); var provider = card(date); var old = card(date, 26); var paid = order(date, false);
        providerExpiry(provider.payment(), "interval '3 days'");
        var cancelled = card(date, 10);
        stubFlow(cancelled, com.example.ecsite.payment.PaymentFlowStatus.CANCELED);
        assertThat(expiredCancellation.cancel(cancelled.order(), 51L, "cancel-admin", null)).isEqualTo(com.example.ecsite.service.payment.ExpiredAuthorizationCancellationService.Outcome.CANCELLED);
        var ids = refreshTransactions.candidates(10_000).stream().map(c -> c.paymentId()).toList();
        assertThat(ids).contains(estimated.payment()).doesNotContain(provider.payment(), old.payment(), cancelled.payment());
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id=?", Integer.class, paid.order())).isZero();
        // The scheduler is not running unless enabled.
        assertThat(context.getBeanNamesForType(com.example.ecsite.service.payment.AuthorizationExpiryRefreshScheduler.class)).isEmpty();
    }

    @Test void aPaymentChangedWhileReadingIsLeftAsTheDatabaseHasIt() {
        var raced = card(nextBusinessDate(), 3);
        org.mockito.Mockito.when(gateway.retrieveAuthorization(flow(raced))).thenAnswer(call -> {
            providerExpiry(raced.payment(), "interval '2 days'"); // a concurrent authorization result recorded PAY.JP's value
            return new com.example.ecsite.payment.AuthorizationRecovery(new com.example.ecsite.payment.AuthorizationResult(
                    com.example.ecsite.payment.AuthorizationResultStatus.AUTHORIZED, flow(raced), null, null, inDays(4)), null);
        });
        var before = new Object[1];
        refresher(new MovableClock()).runPass();
        before[0] = expiryRow(raced.payment());
        assertThat(expiryRow(raced.payment())).containsEntry("source", "PROVIDER");
        assertThat((java.sql.Timestamp) expiryRow(raced.payment()).get("expires_at")).isBefore(java.sql.Timestamp.valueOf(LocalDateTime.now().plusDays(3)));
        assertThat(refreshTransactions.record(new com.example.ecsite.service.payment.AuthorizationExpiryRefreshService.Candidate(
                raced.order(), raced.payment(), flow(raced)), inDays(4)))
                .isEqualTo(com.example.ecsite.service.payment.AuthorizationExpiryRefreshService.Outcome.STALE);
        assertThat(expiryRow(raced.payment())).isEqualTo(before[0]);
    }

    // ---- C-3: hold history ----

    @Test void heldOrdersAreRecordedPerBusinessDateUntilTheyAreExported() {
        var date = nextBusinessDate(); var held = card(date); var ok = card(date);
        long late = lateAuthorization(held);

        var first = runner(date).run(date);
        var pending = holdStore.pending(held.order());
        assertThat(pending).singleElement().satisfies(h -> {
            assertThat(h.reason()).isEqualTo(Reason.PAYMENT_NOT_READY);
            assertThat(h.lastBusinessDate()).isEqualTo(date);
            assertThat(h.firstBusinessDate()).isEqualTo(date);
            assertThat(h.heldBatches()).isEqualTo(1);
            assertThat(h.overdue()).isFalse();
            assertThat(h.label()).isNotBlank();
            assertThat(h.guidance()).isNotBlank();
        });
        assertThat(holdStore.pending(ok.order())).isEmpty();
        assertThat(jdbc.queryForObject("select batch_id from warehouse_export_holds where order_id=?", Long.class, held.order()))
                .isEqualTo(first.batchId());

        runner(date).run(date.plusDays(1));
        assertThat(holdStore.pending(held.order())).singleElement().satisfies(h -> {
            assertThat(h.lastBusinessDate()).isEqualTo(date.plusDays(1));
            assertThat(h.firstBusinessDate()).isEqualTo(date);
            assertThat(h.heldBatches()).isEqualTo(2);
            assertThat(h.overdue()).isTrue();
        });
        assertThat(holdStore.pending(null)).extracting(WarehouseExportHoldStore.Held::orderId).contains(held.order());

        jdbc.update("update payments set status='CANCELLED' where id=?", late);
        runner(date).run(date.plusDays(2));
        assertThat(holdStore.pending(held.order())).isEmpty();
        assertThat(lines(held.order())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_export_holds where order_id=?", Integer.class, held.order())).isEqualTo(2);
    }

    @Test void aResumedPassKeepsOneHoldPerBatchAndHoldsAreAppendOnly() {
        var date = nextBusinessDate(); var held = card(date); lateAuthorization(held);
        long batch = batch(date);
        assertThat(service.export(batch, held.order()).held()).isEqualTo(Reason.PAYMENT_NOT_READY);
        jdbc.update("update orders set status='CANCELLED', cancelled_at=ordered_at where id=?", held.order());
        assertThat(service.export(batch, held.order()).held()).isEqualTo(Reason.NOT_EXPORTABLE_STATUS);
        assertThat(jdbc.queryForList("select reason from warehouse_export_holds where order_id=?", String.class, held.order()))
                .containsExactly("PAYMENT_NOT_READY");
        // A cancelled order no longer waits for an export.
        assertThat(holdStore.pending(held.order())).isEmpty();
        rejects("55000", () -> jdbc.update("update warehouse_export_holds set reason='FAILED' where order_id=?", held.order()));
        rejects("55000", () -> jdbc.update("delete from warehouse_export_holds where order_id=?", held.order()));
        batches.seal(batch);
        var other = card(date);
        rejects("23514", () -> jdbc.update("insert into warehouse_export_holds(batch_id,order_id,reason) values (?,?,'FAILED')", batch, other.order()));
        holdStore.recordQuietly(batch, other.order(), Reason.FAILED);  // best effort: refused silently
        assertThat(jdbc.queryForObject("select count(*) from warehouse_export_holds where order_id=?", Integer.class, other.order())).isZero();
    }

    @AfterEach void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    /** A runner limited to orders from {@code startDate}, so other tests' fixtures are never swept into its batches. */
    WarehouseExportRunner runner(LocalDate startDate) {
        return new WarehouseExportRunner(batches, service, holdStore, finalDecisions, orders, startDate.toString(), 1);
    }

    void authenticate(long id, String username) {
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        var principal = new com.example.ecsite.security.AdminUserDetails(id, username, "password", true, authorities);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, "password", authorities));
    }

    long lateAuthorization(Fixture f) {
        return tx(() -> {
            var now = orders.findCurrentTimeInTokyo(); var order = orders.findById(f.order()).orElseThrow();
            var late = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 1000, now);
            late.setProviderPaymentId("pfw_late_" + f.order(), now); late.markAuthorized(now); payments.saveAndFlush(late);
            return late.getId();
        });
    }

    // ---- C-2: daily runner ----

    @Test void theRunnerSealsOnePassAndAHeldOrderWaitsForTheNextDay() {
        var date = nextBusinessDate(); var ok = card(date); var held = card(date);
        long late = lateAuthorization(held);

        var first = runner(date).run(date);
        assertThat(first.status()).isEqualTo(WarehouseExportRunner.Status.SEALED);
        assertThat(first.exported()).isEqualTo(1);
        assertThat(first.held()).isEqualTo(1);
        assertThat(first.lineCount()).isEqualTo(1);
        assertThat(lines(ok.order())).isEqualTo(1);
        assertUnprepared(held.order());

        // The unnecessary authorization is cancelled later the same day: still not added to the sealed batch.
        jdbc.update("update payments set status='CANCELLED' where id=?", late);
        var again = runner(date).run(date);
        assertThat(again.status()).isEqualTo(WarehouseExportRunner.Status.ALREADY_SEALED);
        assertThat(again.batchId()).isEqualTo(first.batchId());
        assertUnprepared(held.order());

        var next = runner(date).run(date.plusDays(1));
        assertThat(next.status()).isEqualTo(WarehouseExportRunner.Status.SEALED);
        assertThat(next.lineCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select batch_id from warehouse_export_lines where order_id=?", Long.class, held.order()))
                .isEqualTo(next.batchId());
    }

    @Test void theRunnerResumesAnInterruptedBatchAndRespectsTheStartDate() {
        var date = nextBusinessDate(); var done = card(date); var pending = card(date);
        long batch = batch(date);
        service.export(batch, done.order());  // a pass that stopped before sealing

        assertThat(runner(date.plusDays(1)).run(date).status()).isEqualTo(WarehouseExportRunner.Status.NOT_STARTED);
        var resumed = runner(date).run(date);
        assertThat(resumed.batchId()).isEqualTo(batch);
        assertThat(resumed.lineCount()).isEqualTo(2);
        assertThat(lines(pending.order())).isEqualTo(1);

        var other = nextBusinessDate();
        assertThat(runner(other.plusDays(1)).run(other).status()).isEqualTo(WarehouseExportRunner.Status.NOT_STARTED);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_export_batches where business_date=?", Integer.class, other)).isZero();
        var tomorrow = jdbc.queryForObject("select (timezone('Asia/Tokyo',clock_timestamp()))::date + 1", LocalDate.class);
        assertThatThrownBy(() -> runner(date).run(tomorrow)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void dueBusinessDateSwitchesAt1405() {
        var day = LocalDate.of(2026, 11, 16);
        assertThat(WarehouseExportRunner.dueBusinessDate(day.atTime(14, 4, 59))).isEqualTo(day.minusDays(1));
        assertThat(WarehouseExportRunner.dueBusinessDate(day.atTime(14, 5))).isEqualTo(day);
    }

    // ---- Feature137-A: warehouse file rows (V101) ----

    @Test void fileRowsAllocateTheTaxOfEachRateAndAddUpToTheOrder() {
        var f = mixed(nextBusinessDate(), 550);
        assertThat(orderRow(f.order())).containsEntry("total_amount", 758).containsEntry("tax_amount", 66);
        // 10%: 54 + 54 + 550 -> floor(658*10/110)=59, rows 4+4+50 plus the remainder 1 on the largest (the fee).
        assertThat(fileRows(f.order())).containsExactly(
                "1|item|商品A|10.00|27|2|54|4", "2|item|商品B, 特別|10.00|54|1|54|4", "3|item|食品C|8.00|100|1|100|7",
                "4|charge|送料・梱包料|10.00|550|1|550|51");
        // Free shipping: no fee row; the 10% remainder goes to the first of the equal largest rows.
        var free = mixed(nextBusinessDate(), 0);
        assertThat(orderRow(free.order())).containsEntry("total_amount", 208).containsEntry("tax_amount", 16);
        assertThat(fileRows(free.order())).containsExactly(
                "1|item|商品A|10.00|27|2|54|5", "2|item|商品B, 特別|10.00|54|1|54|4", "3|item|食品C|8.00|100|1|100|7");
    }

    @Test void anExportLineStoresTheFileRowsAndASealedBatchBecomesTwoFiles() {
        var date = nextBusinessDate(); long batch = batch(date);
        var plain = card(date); var mixed = mixed(date, 550);
        jdbc.update("update orders set shipping_address_line='千代田1-1, ビル\"A\"' where id=?", mixed.order());
        assertThat(service.export(batch, plain.order()).isExported()).isTrue();
        assertThat(service.export(batch, mixed.order()).isExported()).isTrue();
        var stored = jdbc.queryForMap("select tax_amount, jsonb_array_length(file_lines) as rows from warehouse_export_lines where order_id=?", mixed.order());
        assertThat(stored).containsEntry("tax_amount", 66).containsEntry("rows", 4);
        assertThatThrownBy(() -> fileSource.forBatch(batch)).isInstanceOf(WarehouseFileSource.NotWritableException.class);
        batches.seal(batch);

        var content = fileSource.forBatch(batch);
        String day = date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String basic = date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        long product = jdbc.queryForObject("select product_id from order_items where id=?", Long.class, plain.item());
        var mixedProducts = jdbc.queryForList("select product_id from order_items where order_id=? order by id", Long.class, mixed.order());
        var header = content.headerFile(); var detail = content.detailFile();
        assertThat(header.filename()).isEqualTo("warehouse-header-" + basic + "-" + batch + ".csv");
        assertThat(detail.filename()).isEqualTo("warehouse-detail-" + basic + "-" + batch + ".csv");
        assertThat(text(header.content())).isEqualTo(WarehouseFileFormat.HEADER_COLUMNS + "\r\n"
                + batch + "," + day + "," + plain.order() + ",山田 太郎,100-0001,東京都,千代田区,千代田1-1,0312345678,682,68,750\r\n"
                + batch + "," + day + "," + mixed.order() + ",山田 太郎,100-0001,東京都,千代田区,\"千代田1-1, ビル\"\"A\"\"\",0312345678,692,66,758\r\n");
        assertThat(text(detail.content())).isEqualTo(WarehouseFileFormat.DETAIL_COLUMNS + "\r\n"
                + batch + "," + plain.order() + ",1," + product + ",ordered,10%,100,2,200,18\r\n"
                + batch + "," + plain.order() + ",2,,送料・梱包料,10%,550,1,550,50\r\n"
                + batch + "," + mixed.order() + ",1," + mixedProducts.get(0) + ",商品A,10%,27,2,54,4\r\n"
                + batch + "," + mixed.order() + ",2," + mixedProducts.get(1) + ",\"商品B, 特別\",10%,54,1,54,4\r\n"
                + batch + "," + mixed.order() + ",3," + mixedProducts.get(2) + ",食品C,8%,100,1,100,7\r\n"
                + batch + "," + mixed.order() + ",4,,送料・梱包料,10%,550,1,550,51\r\n");
        assertThat(header.rowCount()).isEqualTo(2); assertThat(detail.rowCount()).isEqualTo(6);
        // The same rows every time (the snapshot never changes).
        assertThat(fileSource.forBatch(batch).detailFile().content()).isEqualTo(detail.content());
        rejects("55000", () -> jdbc.update("update warehouse_export_lines set tax_amount=0 where order_id=?", mixed.order()));
    }

    @Test void callersCannotChooseTheStoredFileRows() {
        var date = nextBusinessDate(); var f = card(date); long batch = batch(date);
        tx(() -> {
            jdbc.update("update orders set shipping_preparation_started_at=timezone('Asia/Tokyo',clock_timestamp()),"
                    + "shipping_preparation_started_by_username='WAREHOUSE_EXPORT' where id=?", f.order());
            return jdbc.update("insert into warehouse_export_lines(batch_id,order_id,tax_amount,file_lines) values (?,?,1,'[{}]'::jsonb)",
                    batch, f.order());
        });
        var stored = jdbc.queryForMap("select tax_amount, file_lines->1->>'productName' as fee, jsonb_array_length(file_lines) as rows"
                + " from warehouse_export_lines where order_id=?", f.order());
        assertThat(stored).containsEntry("tax_amount", 68).containsEntry("fee", "送料・梱包料").containsEntry("rows", 2);
    }

    @Test void anOrderWhoseRowsDoNotAddUpIsHeldAndTheDatabaseRefusesItsLine() {
        var date = nextBusinessDate(); long batch = batch(date);
        var wrongTax = card(date);
        jdbc.update("update orders set tax_amount=tax_amount+1 where id=?", wrongTax.order());
        assertHeld(wrongTax, batch, Reason.AMOUNT_MISMATCH);
        assertThat(jdbc.queryForList("select reason from warehouse_export_holds where order_id=?", String.class, wrongTax.order()))
                .containsExactly("AMOUNT_MISMATCH");
        var wrongItem = card(date);
        jdbc.update("update order_items set subtotal=subtotal+1 where order_id=?", wrongItem.order());
        assertHeld(wrongItem, batch, Reason.AMOUNT_MISMATCH);
        assertThat(WarehouseExportHoldStore.label(Reason.AMOUNT_MISMATCH)).isNotBlank();
        assertThat(WarehouseExportHoldStore.guidance(Reason.AMOUNT_MISMATCH)).isNotBlank();

        // Database: the same sums are checked when the line is written.
        var late = card(date.minusDays(1)); long lateBatch = batch(date.minusDays(1));
        jdbc.update("update orders set tax_amount=tax_amount-1 where id=?", late.order());
        assertThatThrownBy(() -> tx(() -> {
            jdbc.update("update orders set shipping_preparation_started_at=timezone('Asia/Tokyo',clock_timestamp()),"
                    + "shipping_preparation_started_by_username='WAREHOUSE_EXPORT' where id=?", late.order());
            return jdbc.update("insert into warehouse_export_lines(batch_id,order_id) values (?,?)", lateBatch, late.order());
        })).hasMessageContaining("do not match the order amounts");
        assertUnprepared(late.order());
    }

    @Test void aSealedEmptyBatchBecomesHeaderOnlyFiles() {
        var date = nextBusinessDate(); long batch = batch(date);
        assertThatThrownBy(() -> fileSource.forBatch(batch)).isInstanceOf(WarehouseFileSource.NotWritableException.class);
        batches.seal(batch);
        var content = fileSource.forBatch(batch);
        assertThat(text(content.headerFile().content())).isEqualTo(WarehouseFileFormat.HEADER_COLUMNS + "\r\n");
        assertThat(text(content.detailFile().content())).isEqualTo(WarehouseFileFormat.DETAIL_COLUMNS + "\r\n");
        assertThatThrownBy(() -> fileSource.forBatch(Long.MAX_VALUE)).isInstanceOf(WarehouseFileSource.NotWritableException.class);
    }

    @Test void anEmergencyOutputComesFromTheFrozenOrderWithItsEBatchId() {
        var f = mixed(nextBusinessDate(), 550);
        adminPrepare(f.order());
        var record = jdbc.queryForMap("select id, prepared_at from emergency_shipping_preparations where order_id=?", f.order());
        long id = ((Number) record.get("id")).longValue();
        var day = ((java.sql.Timestamp) record.get("prepared_at")).toLocalDateTime().toLocalDate();

        var content = fileSource.forEmergency(id);
        assertThat(content.batchId()).isEqualTo("E" + id);
        assertThat(content.businessDate()).isEqualTo(day);
        assertThat(content.headerFile().filename())
                .isEqualTo("warehouse-header-" + day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-E" + id + ".csv");
        assertThat(text(content.headerFile().content()).split("\r\n")[1]).isEqualTo("E" + id + ","
                + day.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "," + f.order()
                + ",山田 太郎,100-0001,東京都,千代田区,千代田1-1,0312345678,692,66,758");
        var detailLines = text(content.detailFile().content()).split("\r\n");
        assertThat(detailLines).hasSize(5);
        assertThat(detailLines[4]).isEqualTo("E" + id + "," + f.order() + ",4,,送料・梱包料,10%,550,1,550,51");
        assertThatThrownBy(() -> fileSource.forEmergency(Long.MAX_VALUE)).isInstanceOf(WarehouseFileSource.NotWritableException.class);
    }

    List<String> fileRows(long order) {
        return jdbc.queryForList("select line_no||'|'||case when order_item_id is null then 'charge' else 'item' end||'|'||product_name"
                + "||'|'||tax_rate||'|'||unit_price||'|'||quantity||'|'||subtotal||'|'||tax_amount from f137_order_file_lines(?)",
                String.class, order);
    }

    static String text(byte[] content) {
        assertThat(content).startsWith(0xEF, 0xBB, 0xBF);
        return new String(content, 3, content.length - 3, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Card order with two 10% items, one 8% item and a 10% shipping fee; amounts as checkout computes them. */
    Fixture mixed(LocalDate businessDate, int shippingFee) {
        Fixture f = tx(() -> {
            var now = orders.findCurrentTimeInTokyo(); String key = UUID.randomUUID().toString();
            var user = new User(); user.setUsername("f137-" + key); user.setPassword("unused"); user.setEnabled(true);
            user.setCreatedAt(now); user.setUpdatedAt(now); users.saveAndFlush(user);
            var category = categories.saveAndFlush(new Category(key));
            var standard = taxes.findAll().stream().filter(t -> "STANDARD".equals(t.getCode())).findFirst().orElseThrow();
            var reduced = taxes.findAll().stream().filter(t -> "REDUCED".equals(t.getCode())).findFirst().orElseThrow();
            var order = new Order(user.getId(), 0, now, now.plusHours(1));
            order.setShippingAddress("山田 太郎", "100-0001", "東京都", "千代田区", "千代田1-1", "0312345678");
            record Line(String name, TaxCategory tax, int price, int quantity) {}
            for (var l : List.of(new Line("商品A", standard, 27, 2), new Line("商品B, 特別", standard, 54, 1), new Line("食品C", reduced, 100, 1))) {
                var product = new Product(); product.setName(l.name()); product.setPrice(l.price()); product.setStock(20);
                product.setCategory(category); product.setTaxCategory(l.tax()); products.saveAndFlush(product);
                order.addItem(new OrderItem(product.getId(), l.name(), category.getId(), category.getName(), l.tax().getId(),
                        l.tax().getCode(), l.tax().getName(), l.tax().getTaxRate(), l.price(), l.quantity()));
            }
            order.addCharge(new OrderCharge(OrderChargeType.SHIPPING, "送料・梱包料", shippingFee, standard.getId(), standard.getCode(),
                    standard.getName(), standard.getTaxRate(), 10));
            int items = 54 + 54 + 100, total = items + shippingFee;
            int tax = (108 + shippingFee) * 10 / 110 + 100 * 8 / 108;
            order.applyAmount(new com.example.ecsite.service.pricing.OrderAmount(items, List.of(), shippingFee, tax, total));
            orders.saveAndFlush(order);
            histories.record(order, null, OrderStatus.ORDERED, OrderStatusHistoryActorType.USER, user.getId(), user.getUsername(), null);
            var payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, total, now);
            payment.setProviderPaymentId("pfw_" + key, now); payment.markAuthorized(now); payments.saveAndFlush(payment);
            var auth = new PaymentTransaction(payment, PaymentTransactionType.AUTHORIZE, total, 0, key, now,
                    PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION);
            auth.markSucceeded(payment.getProviderPaymentId(), now); paymentTransactions.saveAndFlush(auth);
            return new Fixture(order.getId(), user.getId(), order.getItems().getFirst().getId(), payment.getId(), businessDate);
        });
        moveDeadline(f.order(), businessDate.atTime(14, 0));
        return f;
    }

    // ---- Feature137-B: warehouse file outputs (V102) ----

    @Test void sealingABatchRecordsItsFileOutputOnceWithFixedNamesAndCounts() {
        var date = nextBusinessDate(); long batch = batch(date);
        var plain = card(date); var mixed = mixed(date, 550);
        service.export(batch, plain.order()); service.export(batch, mixed.order());
        assertThat(fileOutputs.forBatch(batch)).isEmpty();
        // The CSV download audit (V87) stays guarded as history.
        rejects("23514", () -> jdbc.update("insert into warehouse_export_downloads(batch_id,admin_account_id,admin_username,line_count,row_count,content_sha256)"
                + " values (?,7,'x',0,0,sha256('x'))", batch));
        batches.seal(batch); batches.seal(batch);

        var output = fileOutputs.forBatch(batch).orElseThrow();
        String basic = date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(output.kind()).isEqualTo(WarehouseFileOutputStore.Kind.REGULAR);
        assertThat(output.state()).isEqualTo(WarehouseFileOutputStore.State.PENDING);
        assertThat(output.outputBatchId()).isEqualTo(String.valueOf(batch));
        assertThat(output.businessDate()).isEqualTo(date);
        assertThat(output.headerFilename()).isEqualTo("warehouse-header-" + basic + "-" + batch + ".csv");
        assertThat(output.detailFilename()).isEqualTo("warehouse-detail-" + basic + "-" + batch + ".csv");
        assertThat(output.headerRows()).isEqualTo(2);
        assertThat(output.detailRows()).isEqualTo(6);
        assertThat(output.attempts()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from warehouse_file_outputs where batch_id=?", Integer.class, batch)).isEqualTo(1);

        // An empty batch gets an output too (header-only files, decision C).
        long empty = batch(nextBusinessDate()); batches.seal(empty);
        assertThat(fileOutputs.forBatch(empty).orElseThrow()).satisfies(o -> {
            assertThat(o.headerRows()).isZero(); assertThat(o.detailRows()).isZero();
        });

        // Only the database creates outputs, once per source, and what an output is never changes.
        rejects("23505", () -> jdbc.update("insert into warehouse_file_outputs(kind,batch_id,output_batch_id,business_date,header_filename,"
                + "detail_filename,header_row_count,detail_row_count,next_attempt_at,created_at) values ('REGULAR',?,'1',now(),'a','b',0,0,now(),now())", batch));
        long open = batch(nextBusinessDate());
        rejects("23514", () -> jdbc.update("insert into warehouse_file_outputs(kind,batch_id,output_batch_id,business_date,header_filename,"
                + "detail_filename,header_row_count,detail_row_count,next_attempt_at,created_at) values ('REGULAR',?,'1',now(),'a','b',0,0,now(),now())", open));
        rejects("55000", () -> jdbc.update("update warehouse_file_outputs set header_filename='x.csv' where id=?", output.id()));
        rejects("55000", () -> jdbc.update("update warehouse_file_outputs set detail_row_count=1 where id=?", output.id()));
        rejects("23514", () -> jdbc.update("update warehouse_file_outputs set state='WRITTEN', attempts=attempts+1 where id=?", output.id()));
        rejects("23514", () -> jdbc.update("update warehouse_file_outputs set state='FAILED', last_error='x' where id=?", output.id()));
        rejects("55000", () -> jdbc.update("update warehouse_file_outputs set attempts=attempts+2 where id=?", output.id()));
        rejects("55000", () -> jdbc.update("delete from warehouse_file_outputs where id=?", output.id()));
        // A plain TRUNCATE is already refused by the retries foreign key (0A000); together, the triggers refuse it.
        rejects("0A000", () -> jdbc.execute("truncate warehouse_file_outputs"));
        rejects("55000", () -> jdbc.execute("truncate warehouse_file_outputs, warehouse_file_output_retries"));
        rejects("55000", () -> jdbc.execute("truncate warehouse_file_output_retries"));
    }

    @Test void aPassWritesBothFilesOnceAndRecordsTheirDigests(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var writer = fileService(dir, 3);
        var date = nextBusinessDate(); long batch = batch(date);
        var f = mixed(date, 550);
        service.export(batch, f.order()); batches.seal(batch);
        long id = fileOutputs.forBatch(batch).orElseThrow().id();

        drain(writer, id);

        var output = fileOutputs.find(id).orElseThrow();
        assertThat(output.state()).isEqualTo(WarehouseFileOutputStore.State.WRITTEN);
        assertThat(output.attempts()).isEqualTo(1);
        assertThat(output.writtenAt()).isNotNull();
        var expected = fileSource.forBatch(batch);
        byte[] header = java.nio.file.Files.readAllBytes(dir.resolve("out").resolve(output.headerFilename()));
        byte[] detail = java.nio.file.Files.readAllBytes(dir.resolve("out").resolve(output.detailFilename()));
        assertThat(header).isEqualTo(expected.headerFile().content());
        assertThat(detail).isEqualTo(expected.detailFile().content());
        assertThat(output.headerSha256()).isEqualTo(java.util.HexFormat.of().formatHex(WarehouseExportCsvService.sha256(header)));
        assertThat(output.detailSha256()).isEqualTo(java.util.HexFormat.of().formatHex(WarehouseExportCsvService.sha256(detail)));
        try (var left = java.nio.file.Files.list(dir.resolve("work"))) { assertThat(left).isEmpty(); }

        // WRITTEN is final: never written again, never re-output.
        writer.runPass();
        assertThat(fileOutputs.find(id).orElseThrow().attempts()).isEqualTo(1);
        rejects("55000", () -> jdbc.update("update warehouse_file_outputs set state='PENDING' where id=?", id));
        var admin = com.example.ecsite.support.EmergencyShippingFixtures.ensurePermittedAdmin(jdbc, 7L, "first");
        authenticate(admin.id(), admin.username());
        assertThatThrownBy(() -> fileOutputs.retry(id)).isInstanceOf(WarehouseFileOutputStore.NotRetryableException.class);
        rejects("55000", () -> jdbc.update("insert into warehouse_file_output_retries(output_id,admin_account_id,admin_username,previous_attempts,requested_at)"
                + " values (?,?,?,0,now())", id, admin.id(), admin.username()));
    }

    @Test void aConflictingFileFailsTheAttemptsAndAnAdministratorReOutputsIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var writer = fileService(dir, 2);
        var date = nextBusinessDate(); long batch = batch(date);
        var f = card(date);
        service.export(batch, f.order()); batches.seal(batch);
        var output = fileOutputs.forBatch(batch).orElseThrow();
        var conflicting = dir.resolve("out").resolve(output.detailFilename());
        java.nio.file.Files.writeString(conflicting, "other");

        drain(writer, output.id());
        var first = fileOutputs.find(output.id()).orElseThrow();
        assertThat(first.state()).isEqualTo(WarehouseFileOutputStore.State.PENDING);
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.lastError()).contains("上書きしません").contains(output.detailFilename());
        assertThat(first.nextAttemptAt()).isAfter(first.lastAttemptedAt());
        assertThat(java.nio.file.Files.readString(conflicting)).isEqualTo("other");
        assertThat(java.nio.file.Files.exists(dir.resolve("out").resolve(output.headerFilename()))).isFalse();

        // Not retried before the retry delay; the last attempt makes it FAILED.
        writer.runPass();
        assertThat(fileOutputs.find(output.id()).orElseThrow().attempts()).isEqualTo(1);
        jdbc.update("update warehouse_file_outputs set next_attempt_at=next_attempt_at-interval '1 hour' where id=?", output.id());
        drain(writer, output.id());
        var failed = fileOutputs.find(output.id()).orElseThrow();
        assertThat(failed.state()).isEqualTo(WarehouseFileOutputStore.State.FAILED);
        assertThat(failed.attempts()).isEqualTo(2);
        rejects("55000", () -> jdbc.update("update warehouse_file_outputs set state='PENDING' where id=?", output.id()));

        // Re-output: an administrator only, recorded, then written by the next pass once the conflict is resolved.
        assertThatThrownBy(() -> fileOutputs.retry(output.id())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        rejects("42501", () -> jdbc.update("insert into warehouse_file_output_retries(output_id,admin_account_id,admin_username,previous_attempts,requested_at)"
                + " values (?,?,'nobody',0,now())", output.id(), Long.MAX_VALUE - 1));
        var admin = com.example.ecsite.support.EmergencyShippingFixtures.ensurePermittedAdmin(jdbc, 7L, "first");
        authenticate(admin.id(), admin.username());
        var reset = fileOutputs.retry(output.id());
        assertThat(reset.state()).isEqualTo(WarehouseFileOutputStore.State.PENDING);
        assertThat(reset.attempts()).isZero();
        assertThat(reset.lastError()).isNull();
        assertThat(fileOutputs.retries(output.id())).singleElement().satisfies(r -> {
            assertThat(r.adminUsername()).isEqualTo("first");
            assertThat(r.previousAttempts()).isEqualTo(2);
            assertThat(r.previousError()).contains("上書きしません");
        });
        rejects("55000", () -> jdbc.update("update warehouse_file_output_retries set admin_username='x' where output_id=?", output.id()));
        rejects("55000", () -> jdbc.update("delete from warehouse_file_output_retries where output_id=?", output.id()));

        java.nio.file.Files.delete(conflicting);
        drain(writer, output.id());
        assertThat(fileOutputs.find(output.id()).orElseThrow().state()).isEqualTo(WarehouseFileOutputStore.State.WRITTEN);
        assertThat(java.nio.file.Files.readAllBytes(conflicting)).isEqualTo(fileSource.forBatch(batch).detailFile().content());
    }

    @Test void anEmergencyShippingRecordsItsOutputAndThePassWritesIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var writer = fileService(dir, 3);
        var f = mixed(nextBusinessDate(), 550);
        adminPrepare(f.order());
        long record = jdbc.queryForObject("select id from emergency_shipping_preparations where order_id=?", Long.class, f.order());
        var output = fileOutputs.forOrder(f.order()).orElseThrow();
        assertThat(output.kind()).isEqualTo(WarehouseFileOutputStore.Kind.EMERGENCY);
        assertThat(output.outputBatchId()).isEqualTo("E" + record);
        assertThat(output.headerFilename()).endsWith("-E" + record + ".csv");
        assertThat(output.headerRows()).isEqualTo(1);
        assertThat(output.detailRows()).isEqualTo(4);

        drain(writer, output.id());
        assertThat(fileOutputs.find(output.id()).orElseThrow().state()).isEqualTo(WarehouseFileOutputStore.State.WRITTEN);
        var expected = fileSource.forEmergency(record);
        assertThat(java.nio.file.Files.readAllBytes(dir.resolve("out").resolve(output.headerFilename())))
                .isEqualTo(expected.headerFile().content());
        assertThat(java.nio.file.Files.readAllBytes(dir.resolve("out").resolve(output.detailFilename())))
                .isEqualTo(expected.detailFile().content());
    }

    /** Feature137-C (decision 8): a prepared order is shown as 出荷準備中 and the admin order list filters on it. */
    @Test void aPreparedOrderIsInShippingPreparationAndTheOrderListFiltersOnIt() {
        var date = nextBusinessDate(); long batch = batch(date);
        var prepared = card(date); var waiting = card(date);
        service.export(batch, prepared.order());
        tx(() -> {
            var p = orders.findById(prepared.order()).orElseThrow(); var w = orders.findById(waiting.order()).orElseThrow();
            assertThat(p.isShippingPreparationInProgress()).isTrue();
            assertThat(p.getStatusDisplayName()).isEqualTo("出荷準備中");
            assertThat(p.getStatus()).isEqualTo(OrderStatus.ORDERED);
            assertThat(w.isShippingPreparationInProgress()).isFalse();
            assertThat(w.getStatusDisplayName()).isEqualTo("注文受付");
            return null;
        });
        assertThat(found(prepared.order(), null, null)).isTrue();
        assertThat(found(prepared.order(), com.example.ecsite.form.AdminShippingPreparationFilter.PREPARING, null)).isTrue();
        assertThat(found(prepared.order(), com.example.ecsite.form.AdminShippingPreparationFilter.NOT_PREPARING, null)).isFalse();
        assertThat(found(waiting.order(), com.example.ecsite.form.AdminShippingPreparationFilter.PREPARING, null)).isFalse();
        assertThat(found(waiting.order(), com.example.ecsite.form.AdminShippingPreparationFilter.NOT_PREPARING, OrderStatus.ORDERED)).isTrue();
        assertThat(found(prepared.order(), com.example.ecsite.form.AdminShippingPreparationFilter.PREPARING, OrderStatus.SHIPPED)).isFalse();
        // Combined with the authorization expiry filter (both optional filters in one query).
        var both = new com.example.ecsite.form.AdminOrderSearchForm();
        both.setOrderId(prepared.order());
        both.setShippingPreparation(com.example.ecsite.form.AdminShippingPreparationFilter.PREPARING);
        both.setAuthorizationExpiry(com.example.ecsite.form.AdminAuthorizationExpiryFilter.EXPIRED);
        assertThat(orderService.searchOrders(both, 7L, 0, 10).getContent()).isEmpty();
    }

    boolean found(long orderId, com.example.ecsite.form.AdminShippingPreparationFilter preparation, OrderStatus status) {
        var form = new com.example.ecsite.form.AdminOrderSearchForm();
        form.setOrderId(orderId); form.setShippingPreparation(preparation); form.setStatus(status);
        return orderService.searchOrders(form, 7L, 0, 10).getContent().stream().anyMatch(o -> o.getId() == orderId);
    }

    WarehouseFileOutputService fileService(java.nio.file.Path dir, int maxAttempts) {
        try {
            java.nio.file.Files.createDirectories(dir.resolve("out")); java.nio.file.Files.createDirectories(dir.resolve("work"));
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        var properties = new com.example.ecsite.config.WarehouseFileProperties(false, dir.resolve("out").toString(),
                dir.resolve("work").toString(), java.time.Duration.ofMinutes(1), java.time.Duration.ofSeconds(10), maxAttempts,
                java.time.Duration.ofMinutes(5), 100);
        return new WarehouseFileOutputService(fileOutputs, fileSource, new WarehouseFileWriter(), properties);
    }

    /** Runs passes until the output has been attempted once more (other tests' outputs are written as well). */
    void drain(WarehouseFileOutputService writer, long outputId) {
        int before = fileOutputs.find(outputId).orElseThrow().attempts();
        for (int pass = 0; pass < 20; pass++) {
            writer.runPass();
            var now = fileOutputs.find(outputId).orElseThrow();
            if (now.attempts() != before || now.state() != WarehouseFileOutputStore.State.PENDING) return;
        }
        throw new AssertionError("output " + outputId + " was not attempted");
    }

    // ---- C-2: shipment must match the export ----

    @Test void anExportedOrderShipsOnlyWithItsExportedPayment() {
        var f = card(nextBusinessDate()); long batch = batch(f.businessDate());
        service.export(batch, f.order());
        long other = tx(() -> {
            var now = orders.findCurrentTimeInTokyo(); var order = orders.findById(f.order()).orElseThrow();
            var p = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, 750, now);
            p.setProviderPaymentId("pfw_other_" + f.order(), now); p.markFailed(now); payments.saveAndFlush(p);
            return p.getId();
        });
        tx(() -> {
            var order = orders.findById(f.order()).orElseThrow();
            assertThat(shipmentGuard.matchesExport(order, f.payment())).isTrue();
            assertThat(shipmentGuard.matchesExport(order, other)).isFalse();
            assertThat(shipmentGuard.matchesExport(order, null)).isFalse();
            return null;
        });
        String ship = "update orders set status='SHIPPED', shipped_at=timezone('Asia/Tokyo',clock_timestamp()),"
                + "shipped_by_account_id=9, shipped_by_username='shipper', shipment_capture_payment_id=? where id=?";
        rejects("23514", () -> jdbc.update(ship, other, f.order()));
        jdbc.update(ship, f.payment(), f.order());
        assertThat(orderRow(f.order())).containsEntry("status", "SHIPPED");
    }

    @Test void anExportedPaidOrderShipsWithoutAPaymentAndAdminPreparedOrdersAreUnaffected() {
        var date = nextBusinessDate(); var paid = order(date, false); var manual = card(date);
        jdbc.update("update orders set status='PAID', paid_at=ordered_at where id=?", paid.order());
        service.export(batch(date), paid.order());
        adminPrepare(manual.order());
        tx(() -> {
            assertThat(shipmentGuard.matchesExport(orders.findById(paid.order()).orElseThrow(), null)).isTrue();
            assertThat(shipmentGuard.matchesExport(orders.findById(paid.order()).orElseThrow(), 1L)).isFalse();
            assertThat(shipmentGuard.matchesExport(orders.findById(manual.order()).orElseThrow(), 123456789L)).isTrue();
            return null;
        });
    }

    private static final AtomicInteger DAYS_AGO = new AtomicInteger(1000 + new Random().nextInt(20000));

    record Fixture(long order, long user, long item, long payment, LocalDate businessDate) {}

    <T> T tx(Supplier<T> body) { return new TransactionTemplate(manager).execute(s -> body.get()); }

    /** Feature136: an administrator (emergency) preparation 10 minutes after the deadline, with its record (V97). */
    void adminPrepare(long orderId) {
        var at = jdbc.queryForObject("select change_deadline_at+interval '10 minutes' from orders where id=?",
                java.time.LocalDateTime.class, orderId);
        com.example.ecsite.support.EmergencyShippingFixtures.prepare(jdbc, orderId, com.example.ecsite.support.EmergencyShippingFixtures.ensurePermittedAdmin(jdbc, 7L, "first"), at, null);
    }

    LocalDate nextBusinessDate() {
        return jdbc.queryForObject("select (timezone('Asia/Tokyo',clock_timestamp()))::date - ?", LocalDate.class,
                DAYS_AGO.addAndGet(3));
    }

    /** Card order (AUTHORIZED PAY.JP payment) whose deadline is 14:00 of {@code businessDate}. */
    Fixture card(LocalDate businessDate) { return order(businessDate, true); }

    Fixture order(LocalDate businessDate, boolean card) { return order(businessDate, card, 0); }

    /** Feature135: {@code paymentAgeDays} moves the payment creation (the V93 estimate base) into the past. */
    Fixture order(LocalDate businessDate, boolean card, int paymentAgeDays) {
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
            // As checkout does (Feature137: the warehouse file rows must add up to the total and tax, V101).
            order.applyAmount(new com.example.ecsite.service.pricing.OrderAmount(price * quantity, java.util.List.of(), 550,
                    java.math.BigDecimal.valueOf(total).multiply(tax.getTaxRate()).divide(
                            java.math.BigDecimal.valueOf(100).add(tax.getTaxRate()), 0, java.math.RoundingMode.DOWN).intValueExact(),
                    total));
            orders.saveAndFlush(order);
            histories.record(order, null, OrderStatus.ORDERED, OrderStatusHistoryActorType.USER, user.getId(), user.getUsername(), null);
            long paymentId = 0;
            if (card) {
                var payment = new Payment(order, PaymentProvider.PAYJP, PaymentMethod.CARD, total, now.minusDays(paymentAgeDays));
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

    @Test void manualPreparationCancellationAndDeadlinesAreRespected() {
        var date = nextBusinessDate(); long batch = batch(date);
        var manual = card(date);
        adminPrepare(manual.order());
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
        adminPrepare(manual.order());

        var found = batches.candidates(date, date.minusDays(1), 0, 1000);

        assertThat(found).contains(due.order(), older.order())
                .doesNotContain(tooOld.order(), later.order(), manual.order());
        assertThat(found).isSorted();
        assertThat(batches.candidates(date, date.minusDays(1), Math.min(due.order(), older.order()), 1000))
                .contains(Math.max(due.order(), older.order())).doesNotContain(Math.min(due.order(), older.order()));
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
