package com.example.ecsite.payment.safety;

import static org.assertj.core.api.Assertions.*;

import java.sql.SQLException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import com.example.ecsite.support.isolation.IsolatedDatabaseGuard;

/** Run through scripts/run-isolated-tests.sh; never uses application/development configuration. */
class PaymentSafetyExpandPostgresTest {
    static String url, user, password;
    String schema;
    PGSimpleDataSource source;
    JdbcTemplate jdbc;

    @BeforeAll static void verifyContainerBeforeAnyDatabaseAccess() {
        url = System.getenv("EC_TEST_DB_URL");
        user = System.getenv("EC_TEST_DB_USER");
        password = System.getenv("EC_TEST_DB_PASSWORD");
        var env = new MockEnvironment();
        for (String prefix : List.of("spring.datasource", "spring.flyway")) {
            env.setProperty(prefix + (prefix.endsWith("flyway") ? ".user" : ".username"), Objects.toString(user, ""));
            env.setProperty(prefix + ".url", Objects.toString(url, ""));
            env.setProperty(prefix + ".password", Objects.toString(password, ""));
        }
        IsolatedDatabaseGuard.verify(env);
    }

    @BeforeEach void isolatedSchema() {
        schema = "expand_" + UUID.randomUUID().toString().replace("-", "");
        source = new PGSimpleDataSource();
        source.setURL(url); source.setUser(user); source.setPassword(password);
        source.setCurrentSchema(schema);
        jdbc = new JdbcTemplate(source);
    }

    void migrate(String target) {
        Flyway.configure().dataSource(source).defaultSchema(schema).target(target).load().migrate();
    }

    long order() {
        long userId = jdbc.queryForObject("insert into users(username,password,created_at,updated_at) values (?,'unused',localtimestamp,localtimestamp) returning id", Long.class, UUID.randomUUID().toString());
        return jdbc.queryForObject("insert into orders(user_id,total_amount,change_deadline_at) values (?,5500,localtimestamp) returning id", Long.class, userId);
    }
    long payment(long order) {
        return jdbc.queryForObject("insert into payments(order_id,provider,payment_method,status,amount,provider_payment_id) values (?,'PAYJP','CARD','AUTHORIZED',5500,?) returning id", Long.class, order, "pfw_" + UUID.randomUUID().toString().replace("-", ""));
    }
    long transaction(long payment, String type, String status, String purpose) {
        return jdbc.queryForObject("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,purpose) values (?,?,?,5500,0,?,?) returning id", Long.class, payment, type, status, UUID.randomUUID().toString(), purpose);
    }
    void rejects(String state, Runnable action) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isNotNull();
        while (!(failure instanceof SQLException) && failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).isInstanceOf(SQLException.class);
        assertThat(((SQLException) failure).getSQLState()).isEqualTo(state);
    }

    @Test void freshDatabaseMigratesThrough55WithNullableColumnsAndUnvalidatedConstraints() {
        migrate("55");
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success", Integer.class)).isEqualTo(55);
        for (String column : List.of("active_payment_id", "purpose")) {
            var row = jdbc.queryForMap("select is_nullable,column_default from information_schema.columns where table_schema=? and column_name=?", schema, column);
            assertThat(row.get("is_nullable")).isEqualTo("YES");
            assertThat(row.get("column_default")).isNull();
        }
        assertThat(jdbc.queryForObject("select collation_name from information_schema.columns where table_schema=? and table_name='payment_transactions' and column_name='purpose'", String.class, schema)).isEqualTo("C");
        assertThat(jdbc.queryForList("select convalidated from pg_constraint where connamespace=?::regnamespace and conname in ('fk_orders_active_payment_owner','chk_payment_transactions_purpose_type_1b')", Boolean.class, schema)).containsExactlyInAnyOrder(false, false);
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname=? and indexname='idx_orders_active_payment'", String.class, schema)).contains("(active_payment_id)", "WHERE (active_payment_id IS NOT NULL)");
    }

    @Test void upgradePreservesEveryLegacyRowAndCaptureBytes() {
        migrate("54");
        long[] fixture = legacyFixture();
        long order = fixture[0], payment = fixture[1], tx = fixture[2];
        var before = legacyRows();
        migrate("55");
        assertThat(legacyRows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select active_payment_id from orders where id=?", Long.class, order)).isNull();
        assertThat(jdbc.queryForObject("select purpose from payment_transactions where id=?", String.class, tx)).isNull();
        // Existing writers omit the new columns and continue to update status only.
        long next = payment(order());
        long nextTx = jdbc.queryForObject("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key) values (?,'UNKNOWN_TYPE','UNKNOWN_STATUS',5500,0,?) returning id", Long.class, next, UUID.randomUUID().toString());
        jdbc.update("update payments set status='CAPTURED' where id=?", payment);
        jdbc.update("update payment_transactions set status='SUCCEEDED',completed_at=localtimestamp where id=?", tx);
        jdbc.update("update orders set total_amount=5400 where id=?", order);
        assertThat(jdbc.queryForMap("select transaction_type,status,purpose from payment_transactions where id=?", nextTx)).containsEntry("transaction_type", "UNKNOWN_TYPE").containsEntry("status", "UNKNOWN_STATUS").containsEntry("purpose", null);
        for (String sql : List.of("update capture_requests set connection_id=connection_id", "delete from capture_requests", "truncate capture_requests")) rejects("55000", () -> jdbc.execute(sql));
    }

    long[] legacyFixture() {
        long order = order(), payment = payment(order);
        jdbc.update("insert into order_status_histories(order_id,to_status,changed_by_type,changed_by_username) values (?,'ORDERED','USER','legacy')", order);
        long tx = jdbc.queryForObject("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key) values (?,'CAPTURE','PENDING',5500,0,'legacy-capture') returning id", Long.class, payment);
        String flow = jdbc.queryForObject("select provider_payment_id from payments where id=?", String.class, payment);
        jdbc.update("insert into capture_requests(payment_id,capture_transaction_id,transaction_type,provider,payment_method,provider_flow_id,connection_id,provider_account_ref,mode,api_origin,http_method,api_path,content_type,amount_to_capture,order_content_revision,idempotency_key,body_format_version,request_body,fingerprint_version,request_fingerprint,created_at,replay_not_after) values (?,?,'CAPTURE','PAYJP','CARD',?,'primary','merchant','TEST','https://api.pay.jp','POST',?,'application/json',5500,0,'legacy-capture',1,convert_to('{\"amount_to_capture\":5500}','UTF8'),1,decode(repeat('ab',32),'hex'),current_timestamp,current_timestamp+interval '23 hours')", payment, tx, flow, "/v2/payment_flows/" + flow + "/capture");
        return new long[] {order, payment, tx};
    }

    Map<String, List<String>> legacyRows() {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("orders", "payments", "payment_transactions", "order_status_histories", "capture_requests")) {
            // Strip only the newly added nullable fields, retaining all old columns including BYTEA hex.
            result.put(table, jdbc.queryForList("select (to_jsonb(t)-'active_payment_id'-'purpose')::text from " + table + " t order by id", String.class));
        }
        return result;
    }

    @Test void activePaymentMustBelongToOrderAndMultiplePaymentsAreAllowed() {
        migrate("55");
        long first = order(), second = order(), p1 = payment(first), p2 = payment(first), foreign = payment(second);
        jdbc.update("update orders set active_payment_id=? where id=?", p1, first);
        jdbc.update("update orders set active_payment_id=? where id=?", p2, first);
        rejects("23503", () -> jdbc.update("update orders set active_payment_id=? where id=?", foreign, first));
        rejects("23503", () -> jdbc.update("delete from payments where id=?", p2));
        rejects("23503", () -> jdbc.update("update payments set order_id=? where id=?", second, p2));
        jdbc.update("update orders set active_payment_id=null where id=?", first);
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id=?", Long.class, first)).isEqualTo(2);
    }

    @ParameterizedTest @CsvSource({"AUTHORIZE,INITIAL_ORDER_AUTHORIZATION", "CANCEL,ORDER_CANCELLATION", "CAPTURE,SHIPMENT_CAPTURE", "UNKNOWN_TYPE,LEGACY_UNKNOWN"})
    void acceptsSupportedPurpose(String type, String purpose) {
        migrate("55");
        long id = transaction(payment(order()), type, "UNKNOWN_STATUS", purpose);
        assertThat(jdbc.queryForObject("select purpose from payment_transactions where id=?", String.class, id)).isEqualTo(purpose);
    }

    @ParameterizedTest @CsvSource({"CAPTURE,INITIAL_ORDER_AUTHORIZATION", "AUTHORIZE,ORDER_CANCELLATION", "CANCEL,SHIPMENT_CAPTURE", "AUTHORIZE,SHIPMENT_CAPTURE", "CAPTURE,ORDER_CANCELLATION", "CANCEL,INITIAL_ORDER_AUTHORIZATION", "AUTHORIZE,ORDER_CHANGE_AUTHORIZATION", "CANCEL,ORDER_CHANGE_OLD_AUTH_CANCEL", "CANCEL,ORDER_CHANGE_NEW_AUTH_ABORT", "UNKNOWN_TYPE,INITIAL_ORDER_AUTHORIZATION", "AUTHORIZE,UNKNOWN_PURPOSE", "authorize,INITIAL_ORDER_AUTHORIZATION", "AUTHORIZE,initial_order_authorization"})
    void rejectsUnsupportedPurpose(String type, String purpose) {
        migrate("55");
        long payment = payment(order());
        rejects("23514", () -> transaction(payment, type, "PENDING", purpose));
    }

    @Test void nullPurposePreservesUnknownRawValues() {
        migrate("55");
        long payment = payment(order());
        jdbc.update("update payments set status='FUTURE_PAYMENT_STATUS' where id=?", payment);
        long id = transaction(payment, "FUTURE_TYPE", "FUTURE_STATUS", null);
        assertThat(jdbc.queryForObject("select status from payments where id=?", String.class, payment)).isEqualTo("FUTURE_PAYMENT_STATUS");
        assertThat(jdbc.queryForMap("select transaction_type,status,purpose from payment_transactions where id=?", id)).containsEntry("transaction_type", "FUTURE_TYPE").containsEntry("status", "FUTURE_STATUS").containsEntry("purpose", null);
    }

    @Test void failedMigrationRollsBackAllEarlierDdlAndPreservesData() {
        migrate("54");
        legacyFixture();
        var before = legacyRows();
        jdbc.execute("create index idx_orders_active_payment on orders(id)");
        assertThatThrownBy(() -> migrate("55")).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(legacyRows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=? and column_name in ('purpose','active_payment_id')", Long.class, schema)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where connamespace=?::regnamespace and conname in ('uq_payments_order_identity','fk_orders_active_payment_owner','chk_payment_transactions_purpose_type_1b')", Long.class, schema)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='55'", Long.class)).isZero();
        jdbc.execute("drop index idx_orders_active_payment");
        migrate("55");
        assertThat(legacyRows()).isEqualTo(before);
    }
}
