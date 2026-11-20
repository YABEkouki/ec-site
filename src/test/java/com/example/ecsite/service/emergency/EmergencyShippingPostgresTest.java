package com.example.ecsite.service.emergency;

import static org.assertj.core.api.Assertions.*;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.repository.AdminAccountRepository;
import com.example.ecsite.support.EmergencyShippingFixtures;
import com.example.ecsite.support.EmergencyShippingFixtures.Admin;

/** Feature136-A: V97 permission history, emergency shipping records and the order preparation rule. */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmergencyShippingPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AdminAccountRepository admins;
    @Autowired EmergencyShippingStore store;

    private long order() {
        long user = jdbc.queryForObject("insert into users(username,password,created_at,updated_at) "
                + "values (?,'unused',localtimestamp,localtimestamp) returning id", Long.class, "f136-" + UUID.randomUUID());
        return jdbc.queryForObject("insert into orders(user_id,total_amount,change_deadline_at,content_revision) "
                + "values (?,1200,timezone('Asia/Tokyo',clock_timestamp())-interval '1 hour',2) returning id", Long.class, user);
    }

    private long payment(long order, String status) {
        return jdbc.queryForObject("insert into payments(order_id,provider,payment_method,status,amount,provider_payment_id) "
                + "values (?,'PAYJP','CARD',?,1200,?) returning id", Long.class, order, status, "pfw_" + UUID.randomUUID());
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("select date_trunc('microseconds',timezone('Asia/Tokyo',clock_timestamp()))::timestamp",
                LocalDateTime.class);
    }

    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(manager).execute(status -> action.get());
    }

    private static void rejects(String sqlState, String message, Runnable action) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).as("expected %s", message).isNotNull();
        while (!(failure instanceof SQLException) && failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).isInstanceOf(SQLException.class).hasMessageContaining(message);
        assertThat(((SQLException) failure).getSQLState()).isEqualTo(sqlState);
    }

    private Map<String, Object> preparation(long order) {
        return jdbc.queryForMap("select shipping_preparation_started_at,shipping_preparation_started_by_account_id,"
                + "shipping_preparation_started_by_username from orders where id=?", order);
    }

    private void assertUnprepared(long order) {
        assertThat(preparation(order).values()).containsOnlyNulls();
        assertThat(jdbc.queryForObject("select count(*) from emergency_shipping_preparations where order_id=?",
                Integer.class, order)).isZero();
    }

    @Test
    void permissionChangesAreRecordedByTriggerOnlyAndAppendOnly() {
        Admin admin = EmergencyShippingFixtures.permittedAdmin(jdbc, "history");
        jdbc.update("update admin_accounts set emergency_shipping_allowed=false where id=?", admin.id());
        jdbc.update("update admin_accounts set emergency_shipping_allowed=false where id=?", admin.id());
        jdbc.update("update admin_accounts set enabled=false where id=?", admin.id());
        var rows = jdbc.queryForList("select allowed,changed_at,changed_by_db_user from admin_emergency_shipping_permission_histories "
                + "where admin_account_id=? order by id", admin.id());
        assertThat(rows).extracting(r -> r.get("allowed")).containsExactly(true, false);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("changed_by_db_user")).isNotNull());
        rejects("55000", "append-only", () -> jdbc.update("insert into admin_emergency_shipping_permission_histories"
                + "(admin_account_id,allowed,changed_at,changed_by_db_user) values (?,true,localtimestamp,'x')", admin.id()));
        rejects("55000", "append-only", () -> jdbc.update(
                "update admin_emergency_shipping_permission_histories set allowed=true where admin_account_id=?", admin.id()));
        rejects("55000", "append-only", () -> jdbc.update(
                "delete from admin_emergency_shipping_permission_histories where admin_account_id=?", admin.id()));
        rejects("55000", "append-only", () -> jdbc.execute("truncate admin_emergency_shipping_permission_histories cascade"));
        // A new account without the flag writes no history.
        long plain = jdbc.queryForObject("insert into admin_accounts(username,password,enabled,created_at,updated_at) "
                + "values (?,'x',true,localtimestamp,localtimestamp) returning id", Long.class, "plain-" + UUID.randomUUID());
        assertThat(jdbc.queryForObject("select count(*) from admin_emergency_shipping_permission_histories where admin_account_id=?",
                Integer.class, plain)).isZero();
    }

    @Test
    void theApplicationNeverWritesThePermissionFlag() {
        Admin admin = EmergencyShippingFixtures.permittedAdmin(jdbc, "jpa");
        tx(() -> {
            AdminAccount account = admins.findById(admin.id()).orElseThrow();
            assertThat(account.isEmergencyShippingAllowed()).isTrue();
            account.setUpdatedAt(dbNow());
            return admins.saveAndFlush(account);
        });
        assertThat(jdbc.queryForObject("select emergency_shipping_allowed from admin_accounts where id=?", Boolean.class,
                admin.id())).isTrue();
        AdminAccount created = tx(() -> {
            AdminAccount account = new AdminAccount();
            account.setUsername("jpa-new-" + UUID.randomUUID()); account.setPassword("x"); account.setEnabled(true);
            account.setCreatedAt(dbNow()); account.setUpdatedAt(dbNow());
            return admins.saveAndFlush(account);
        });
        assertThat(jdbc.queryForObject("select emergency_shipping_allowed from admin_accounts where id=?", Boolean.class,
                created.getId())).isFalse();
        assertThat(store.isAllowed(admin.id())).isTrue();
        assertThat(store.isAllowed(created.getId())).isFalse();
        assertThat(store.isAllowed(Long.MAX_VALUE)).isFalse();
        assertThat(store.isAllowed(null)).isFalse();
        jdbc.update("update admin_accounts set enabled=false where id=?", admin.id());
        assertThat(store.isAllowed(admin.id())).isFalse();
    }

    @Test
    void recordAndPreparationCommitTogetherWithADatabaseSnapshot() {
        Admin admin = EmergencyShippingFixtures.permittedAdmin(jdbc, "snapshot");
        long order = order(); long payment = payment(order, "AUTHORIZED");
        jdbc.update("update orders set active_payment_id=? where id=?", payment, order);
        LocalDateTime before = dbNow();
        LocalDateTime at = EmergencyShippingFixtures.prepare(jdbc, order, admin, null, null);
        var record = store.find(order).orElseThrow();
        assertThat(record.adminAccountId()).isEqualTo(admin.id());
        assertThat(record.adminUsername()).isEqualTo(admin.username());
        assertThat(record.reason()).isEqualTo("test fixture");
        assertThat(record.preparedAt()).isEqualTo(at);
        // The fixture passed placeholders; the trigger took the values from the locked order and the DB clock.
        assertThat(record.orderStatus()).isEqualTo("ORDERED");
        assertThat(record.contentRevision()).isEqualTo(2);
        assertThat(record.totalAmount()).isEqualTo(1200);
        assertThat(record.activePaymentId()).isEqualTo(payment);
        assertThat(record.changeDeadlineAt()).isBefore(at);
        assertThat(record.createdAt()).isAfterOrEqualTo(before).isAfterOrEqualTo(at);
        assertThat(preparation(order)).containsEntry("shipping_preparation_started_by_account_id", admin.id())
                .containsEntry("shipping_preparation_started_by_username", admin.username());
    }

    @Test
    void administratorPreparationRequiresItsRecord() {
        Admin admin = EmergencyShippingFixtures.permittedAdmin(jdbc, "required");
        long order = order();
        rejects("23514", "requires an emergency shipping record", () -> jdbc.update(
                "update orders set shipping_preparation_started_at=timezone('Asia/Tokyo',clock_timestamp()),"
                + "shipping_preparation_started_by_account_id=?,shipping_preparation_started_by_username=? where id=?",
                admin.id(), admin.username(), order));
        // A record for another administrator or time does not count.
        LocalDateTime at = dbNow();
        rejects("23514", "requires an emergency shipping record", () -> tx(() -> {
            EmergencyShippingFixtures.record(jdbc, order, admin, at);
            return jdbc.update("update orders set shipping_preparation_started_at=?,"
                    + "shipping_preparation_started_by_account_id=?,shipping_preparation_started_by_username=? where id=?",
                    at.minusSeconds(1), admin.id(), admin.username(), order);
        }));
        assertUnprepared(order);
        // A record without the matching preparation fails at commit.
        rejects("23514", "requires the matching shipping preparation",
                () -> tx(() -> { EmergencyShippingFixtures.record(jdbc, order, admin, at); return null; }));
        assertUnprepared(order);
        // An order cannot be inserted already prepared by an administrator.
        long user = jdbc.queryForObject("select user_id from orders where id=?", Long.class, order);
        rejects("23514", "requires an emergency shipping record", () -> jdbc.update(
                "insert into orders(user_id,total_amount,change_deadline_at,shipping_preparation_started_at,"
                + "shipping_preparation_started_by_account_id,shipping_preparation_started_by_username) "
                + "values (?,100,localtimestamp,localtimestamp,?,?)", user, admin.id(), admin.username()));
    }

    @Test
    void recordRequiresAPermittedEnabledAdministratorWithTheSameName() {
        Admin admin = EmergencyShippingFixtures.permittedAdmin(jdbc, "permission");
        long order = order();
        LocalDateTime at = dbNow();
        rejects("42501", "not allowed", () -> EmergencyShippingFixtures.prepare(jdbc, order,
                new Admin(admin.id(), "someone-else"), at, null));
        jdbc.update("update admin_accounts set emergency_shipping_allowed=false where id=?", admin.id());
        rejects("42501", "not allowed", () -> EmergencyShippingFixtures.prepare(jdbc, order, admin, at, null));
        jdbc.update("update admin_accounts set emergency_shipping_allowed=true, enabled=false where id=?", admin.id());
        rejects("42501", "not allowed", () -> EmergencyShippingFixtures.prepare(jdbc, order, admin, at, null));
        rejects("42501", "not allowed", () -> EmergencyShippingFixtures.prepare(jdbc, order,
                new Admin(Long.MAX_VALUE, "missing"), at, null));
        assertUnprepared(order);
    }

    @Test
    void recordIsRefusedWhileWorkIsUnresolvedOrTheOrderIsNotACandidate() {
        Admin admin = EmergencyShippingFixtures.permittedAdmin(jdbc, "conflict");
        // Pending payment work.
        long pending = order(); long payment = payment(pending, "AUTHORIZED");
        jdbc.update("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,"
                + "idempotency_key,created_at) values (?,'CAPTURE','PENDING',1200,2,?,localtimestamp)", payment, UUID.randomUUID().toString());
        rejects("23514", "Unresolved order change or payment work", () -> EmergencyShippingFixtures.prepare(jdbc, pending, admin, null, null));
        assertUnprepared(pending);
        // Open payment discrepancy.
        long discrepancy = order(); long other = payment(discrepancy, "AUTHORIZED");
        jdbc.update("insert into payment_discrepancies(payment_id,local_status,provider_status,status,first_detected_at,"
                + "last_detected_at,created_at,updated_at) values (?,'AUTHORIZED','CANCELED','OPEN',localtimestamp,localtimestamp,"
                + "localtimestamp,localtimestamp)", other);
        assertThat(store.hasOpenPaymentDiscrepancy(discrepancy)).isTrue();
        rejects("23514", "open payment discrepancy", () -> EmergencyShippingFixtures.prepare(jdbc, discrepancy, admin, null, null));
        assertUnprepared(discrepancy);
        // Terminal status and a missing order.
        long cancelled = order();
        jdbc.update("update orders set status='CANCELLED', cancelled_at=localtimestamp where id=?", cancelled);
        rejects("23514", "cannot be shipped in an emergency", () -> EmergencyShippingFixtures.prepare(jdbc, cancelled, admin, null, null));
        rejects("23514", "requires an existing order", () -> tx(() -> {
            EmergencyShippingFixtures.record(jdbc, Long.MAX_VALUE, admin, dbNow()); return null; }));
    }

    @Test
    void recordsAreAppendOnlyAndTheEmergencyPreparationIsFixed() {
        Admin admin = EmergencyShippingFixtures.permittedAdmin(jdbc, "fixed");
        long order = order();
        EmergencyShippingFixtures.prepare(jdbc, order, admin, null, null);
        var stored = preparation(order);
        rejects("55000", "append-only", () -> jdbc.update("update emergency_shipping_preparations set reason='changed' where order_id=?", order));
        rejects("55000", "append-only", () -> jdbc.update("delete from emergency_shipping_preparations where order_id=?", order));
        rejects("55000", "append-only", () -> jdbc.execute("truncate emergency_shipping_preparations cascade"));
        rejects("55000", "cannot be changed", () -> jdbc.update("update orders set shipping_preparation_started_at=null,"
                + "shipping_preparation_started_by_account_id=null,shipping_preparation_started_by_username=null where id=?", order));
        rejects("55000", "cannot be changed", () -> jdbc.update(
                "update orders set shipping_preparation_started_at=shipping_preparation_started_at+interval '1 second' where id=?", order));
        // A second record for the same order is refused (the order is already prepared).
        rejects("55000", "already started", () -> tx(() -> { EmergencyShippingFixtures.record(jdbc, order, admin, dbNow()); return null; }));
        assertThat(preparation(order)).isEqualTo(stored);
        assertThat(jdbc.queryForObject("select count(*) from emergency_shipping_preparations where order_id=?", Integer.class, order)).isOne();
    }
}
