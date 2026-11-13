package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.cart.CartItem;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.payment.*;

/**
 * Feature127: formal acceptance is recorded with the successful initial authorization, from the
 * database clock under the Order lock, together with immutable evidence. Only PaymentGateway is mocked.
 */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@com.example.ecsite.support.CaptureConnectionTest
class OrderFormalAcceptancePostgresTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    @Autowired OrderService orderService;
    @Autowired OrderDeadlineCalculator deadlines;
    @Autowired OrderShippingPreparationService preparation;
    @Autowired PaymentAuthorizationService authorization;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired AdminAccountRepository admins;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired TaxCategoryService taxes;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void threeDsPendingOrderIsUnacceptedAndAuthorizationSuccessAcceptsFromDatabaseTime() {
        var f = checkout();
        assertThat(order(f.order())).containsEntry("acceptance_mode", "AUTHORIZED_ACCEPTANCE")
                .containsEntry("accepted_at", null).containsEntry("change_deadline_at", null);

        // 3-D Secure in progress: still no acceptance, no deadline, never a shipping candidate.
        result(f, AuthorizationResultStatus.REQUIRES_ACTION);
        assertThat(authorization.refreshAuthorization(f.order(), f.payment()).status())
                .isEqualTo(AuthorizationResultStatus.REQUIRES_ACTION);
        assertThat(order(f.order())).containsEntry("accepted_at", null).containsEntry("change_deadline_at", null);
        assertThat(acceptanceCount(f.order())).isZero();
        authenticateAdmin();
        assertThatThrownBy(() -> preparation.start(f.order())).isInstanceOf(IllegalStateException.class);
        assertThat(order(f.order())).containsEntry("shipping_preparation_started_at", null);

        result(f, AuthorizationResultStatus.AUTHORIZED);
        LocalDateTime before = dbNow();
        assertThat(authorization.refreshAuthorization(f.order(), f.payment()).status())
                .isEqualTo(AuthorizationResultStatus.AUTHORIZED);
        LocalDateTime after = dbNow();

        LocalDateTime acceptedAt = date(f.order(), "accepted_at");
        LocalDateTime deadline = date(f.order(), "change_deadline_at");
        assertThat(acceptedAt).isBetween(before, after);
        assertThat(deadline).isEqualTo(deadlines.calculate(acceptedAt));
        var evidence = jdbc.queryForMap("select * from order_acceptances where order_id=?", f.order());
        assertThat(evidence).containsEntry("payment_id", f.payment())
                .containsEntry("authorization_transaction_id", authorizationTransaction(f))
                .containsEntry("accepted_at", Timestamp.valueOf(acceptedAt))
                .containsEntry("change_deadline_at", Timestamp.valueOf(deadline));
        // Audit: the evidence xid committed, and its commit timestamp is at or after the acceptance stamp.
        var commit = jdbc.queryForObject("select pg_xact_commit_timestamp(acceptance_xid::text::xid) from order_acceptances where order_id=?",
                OffsetDateTime.class, f.order());
        assertThat(jdbc.queryForObject("select pg_xact_status(acceptance_xid) from order_acceptances where order_id=?",
                String.class, f.order())).isEqualTo("committed");
        var acceptedInstant = acceptedAt.atZone(TOKYO).toInstant();
        assertThat(commit.toInstant()).isAfterOrEqualTo(acceptedInstant);
        assertThat(Duration.between(acceptedInstant, commit.toInstant())).isLessThan(Duration.ofSeconds(10));

        // Repeated observation (customer return after webhook) does not re-accept.
        assertThat(authorization.refreshAuthorization(f.order(), f.payment()).status())
                .isEqualTo(AuthorizationResultStatus.AUTHORIZED);
        assertThat(date(f.order(), "accepted_at")).isEqualTo(acceptedAt);
        assertThat(acceptanceCount(f.order())).isOne();
    }

    @Test void concurrentWebhookAndCustomerReturnAcceptExactlyOnce() throws Exception {
        var f = checkout();
        result(f, AuthorizationResultStatus.AUTHORIZED);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            List<Future<AuthorizationResultStatus>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(workers.submit(() -> {
                start.await();
                return authorization.refreshAuthorization(f.order(), f.payment()).status();
            }));
            start.countDown();
            for (var future : futures) assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(AuthorizationResultStatus.AUTHORIZED);
        } finally {
            workers.shutdownNow();
        }
        assertThat(acceptanceCount(f.order())).isOne();
        assertThat(jdbc.queryForObject("select o.accepted_at=a.accepted_at and o.change_deadline_at=a.change_deadline_at "
                + "from orders o join order_acceptances a on a.order_id=o.id where o.id=?", Boolean.class, f.order())).isTrue();
    }

    @Test void failedAuthorizationNeverAccepts() {
        var f = checkout();
        result(f, AuthorizationResultStatus.FAILED);
        authorization.refreshAuthorization(f.order(), f.payment());
        assertThat(order(f.order())).containsEntry("status", "CANCELLED").containsEntry("accepted_at", null)
                .containsEntry("change_deadline_at", null);
        assertThat(acceptanceCount(f.order())).isZero();
    }

    @Test void databaseRejectsCallerTimestampsMissingEvidenceAndChanges() {
        var f = checkout();
        // Caller-supplied (non-current-transaction) time is rejected.
        assertRejected("Acceptance time must be the current transaction database time", () -> jdbc.update("update orders set accepted_at=timezone('Asia/Tokyo',clock_timestamp())-interval '1 hour', "
                + "change_deadline_at=date_trunc('day',timezone('Asia/Tokyo',clock_timestamp())-interval '1 hour')+interval '1 day 14 hours' where id=?", f.order()));
        // Deadline not following the 14:00 rule is rejected.
        assertRejected("ck_orders_acceptance_deadline", () -> jdbc.update("update orders set accepted_at=timezone('Asia/Tokyo',clock_timestamp()), "
                + "change_deadline_at=timezone('Asia/Tokyo',clock_timestamp())+interval '3 days' where id=?", f.order()));
        // Acceptance without evidence cannot commit (deferred check).
        assertRejected("must commit with its evidence", () -> jdbc.update("update orders set accepted_at=v.t, change_deadline_at=case when v.t<date_trunc('day',v.t)+interval '14 hours' "
                + "then date_trunc('day',v.t)+interval '14 hours' else date_trunc('day',v.t)+interval '1 day 14 hours' end "
                + "from (select timezone('Asia/Tokyo',clock_timestamp()) t) v where id=?", f.order()));
        // Evidence for an unaccepted order / unsuccessful authorization is rejected.
        assertRejected("does not match the accepted order", () -> jdbc.update("insert into order_acceptances(order_id,payment_id,authorization_transaction_id,accepted_at,change_deadline_at) "
                + "values(?,?,?,timezone('Asia/Tokyo',clock_timestamp()),timezone('Asia/Tokyo',clock_timestamp())+interval '1 day')",
                f.order(), f.payment(), authorizationTransaction(f)));
        // Acceptance mode is fixed.
        assertRejected("acceptance mode is immutable", () -> jdbc.update("update orders set acceptance_mode='LEGACY', change_deadline_at=ordered_at+interval '1 day' where id=?", f.order()));
        assertThat(order(f.order())).containsEntry("accepted_at", null).containsEntry("change_deadline_at", null);

        result(f, AuthorizationResultStatus.AUTHORIZED);
        authorization.refreshAuthorization(f.order(), f.payment());
        var accepted = order(f.order());
        assertRejected("Formal order acceptance is immutable", () -> jdbc.update("update orders set accepted_at=accepted_at+interval '1 second' where id=?", f.order()));
        assertRejected("Formal order acceptance is immutable", () -> jdbc.update("update orders set change_deadline_at=change_deadline_at+interval '1 day' where id=?", f.order()));
        assertRejected("append-only", () -> jdbc.update("update order_acceptances set recorded_at=recorded_at where order_id=?", f.order()));
        assertRejected("append-only", () -> jdbc.update("delete from order_acceptances where order_id=?", f.order()));
        assertThat(order(f.order())).isEqualTo(accepted);
        assertThat(acceptanceCount(f.order())).isOne();
    }

    @Test void legacyRowsKeepTheirStoredDeadlineAndCannotBeInsertedAsAccepted() {
        var f = checkout();
        assertRejected("cannot be inserted as formally accepted", () -> jdbc.update("insert into orders(user_id,total_amount,status,ordered_at,change_deadline_at,item_subtotal,charge_total,tax_amount,"
                + "acceptance_mode,accepted_at) select user_id,total_amount,status,ordered_at,change_deadline_at,item_subtotal,charge_total,tax_amount,"
                + "'AUTHORIZED_ACCEPTANCE',timezone('Asia/Tokyo',clock_timestamp()) from orders where id=?", f.order()));
        assertRejected("ck_orders_legacy_deadline", () -> jdbc.update("insert into orders(user_id,total_amount,status,ordered_at,item_subtotal,charge_total,tax_amount) "
                + "select user_id,total_amount,status,ordered_at,item_subtotal,charge_total,tax_amount from orders where id=?", f.order()));
        assertThat(jdbc.queryForObject("select count(*) from orders where acceptance_mode='LEGACY' and (change_deadline_at is null or accepted_at is not null)",
                Integer.class)).isZero();
    }

    /** Runs in its own transaction (so deferred checks fire at commit) and checks the rejection reason. */
    private void assertRejected(String reason, Runnable action) {
        Throwable thrown = catchThrowable(() -> new TransactionTemplate(manager).executeWithoutResult(s -> action.run()));
        assertThat(thrown).isNotNull();
        StringBuilder messages = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) messages.append(t.getMessage()).append('\n');
        assertThat(messages.toString()).contains(reason);
    }

    private Fixture checkout() {
        SecurityContextHolder.clearContext();
        long[] seed = tx(() -> {
            var now = orders.findCurrentTimeInTokyo(); String unique = UUID.randomUUID().toString();
            User user = new User(); user.setUsername("f127-" + unique); user.setPassword("test"); user.setEnabled(true);
            user.setCreatedAt(now); user.setUpdatedAt(now); users.saveAndFlush(user);
            var category = categories.saveAndFlush(new Category(unique));
            Product product = new Product(); product.setName("f127 product"); product.setPrice(1000); product.setStock(10);
            product.setCategory(category); product.setTaxCategory(taxes.findStandardTaxCategory()); products.saveAndFlush(product);
            return new long[] {user.getId(), product.getId()};
        });
        Cart cart = new Cart(); cart.addItem(new CartItem(seed[1], "f127 product", 1000, 1));
        Order created = orderService.createOrder(seed[0], "customer", cart, address());
        String provider = "pfw_flow_f127_" + UUID.randomUUID();
        when(gateway.prepareAuthorization(any())).thenReturn(new AuthorizationPreparation(provider, "test-secret"));
        var prepared = authorization.prepareAuthorization(created);
        return new Fixture(created.getId(), prepared.paymentId(), provider);
    }

    private void result(Fixture f, AuthorizationResultStatus status) {
        String code = status == AuthorizationResultStatus.FAILED ? "card_declined" : null;
        when(gateway.retrieveAuthorization(f.provider())).thenReturn(new AuthorizationRecovery(
                new AuthorizationResult(status, f.provider(), code, code), null));
    }

    private void authenticateAdmin() {
        var now = orders.findCurrentTimeInTokyo();
        AdminAccount admin = tx(() -> {
            AdminAccount a = new AdminAccount(); a.setUsername("f127-admin-" + UUID.randomUUID()); a.setPassword("test");
            a.setEnabled(true); a.setCreatedAt(now); a.setUpdatedAt(now); return admins.saveAndFlush(a);
        });
        var details = new AdminUserDetails(admin.getId(), admin.getUsername(), "test", true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(details, "", details.getAuthorities()));
    }

    private CheckoutForm address() {
        CheckoutForm f = new CheckoutForm(); f.setShippingName("customer"); f.setShippingPostalCode("100-0001");
        f.setShippingPrefecture("東京都"); f.setShippingCity("千代田区"); f.setShippingAddressLine("1-1"); f.setShippingPhone("0312345678");
        return f;
    }

    private long authorizationTransaction(Fixture f) {
        return jdbc.queryForObject("select id from payment_transactions where payment_id=? and transaction_type='AUTHORIZE'", Long.class, f.payment());
    }
    private int acceptanceCount(long order) {
        return jdbc.queryForObject("select count(*) from order_acceptances where order_id=?", Integer.class, order);
    }
    private Map<String, Object> order(long id) { return jdbc.queryForMap("select * from orders where id=?", id); }
    private LocalDateTime date(long id, String column) {
        return jdbc.queryForObject("select " + column + " from orders where id=?", LocalDateTime.class, id);
    }
    private LocalDateTime dbNow() { return jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())", LocalDateTime.class); }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s -> action.get()); }

    record Fixture(long order, long payment, String provider) {}
}
