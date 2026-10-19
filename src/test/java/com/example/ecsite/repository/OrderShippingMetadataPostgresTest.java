package com.example.ecsite.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderShippingMetadataPostgresTest {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature123_shipping_isolated");

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.flyway.url", DB::getJdbcUrl);
        r.add("spring.flyway.user", DB::getUsername);
        r.add("spring.flyway.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orders;

    long order() {
        long user = jdbc.queryForObject("insert into users(username,password,created_at,updated_at) values('shipping-test','unused',localtimestamp,localtimestamp) returning id", Long.class);
        return jdbc.queryForObject("insert into orders(user_id,total_amount,change_deadline_at) values(?,100,localtimestamp) returning id", Long.class, user);
    }

    void prepare(long id) {
        jdbc.update("update orders set shipping_preparation_started_at=timestamp '2026-10-16 10:00:00', shipping_preparation_started_by_account_id=7, shipping_preparation_started_by_username='admin' where id=?", id);
    }

    void ship(long id) {
        prepare(id);
        jdbc.update("update orders set status='SHIPPED', shipped_at=localtimestamp, shipped_by_account_id=8, shipped_by_username='shipper' where id=?", id);
    }

    void rejects(long id, String assignment, String constraint, String state) {
        rejects(() -> jdbc.update("update orders set " + assignment + " where id=?", id), constraint, state);
    }

    void rejects(Runnable operation, String constraint, String state) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).isNotNull();
        while (!(failure instanceof SQLException) && failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).isInstanceOf(SQLException.class).hasMessageContaining(constraint);
        assertThat(((SQLException) failure).getSQLState()).isEqualTo(state);
    }

    @Test void migratesExistingV52Orders() throws Exception {
        var config = org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .defaultSchema("legacy_shipping");
        config.target("52").load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("set search_path to legacy_shipping");
            statement.execute("insert into users(id,username,password,created_at,updated_at) values(99999,'legacy','unused',localtimestamp,localtimestamp)");
            statement.execute("insert into orders(id,user_id,total_amount,status,change_deadline_at) values(99999,99999,100,'SHIPPED',localtimestamp)");
        }
        config.target("53").load().migrate();
        assertThat(jdbc.queryForObject("select count(*) from legacy_shipping.orders where id=99999 and shipping_preparation_started_at is null and shipment_capture_payment_id is null", Integer.class)).isEqualTo(1);
    }

    @Test void migrationAndLegacyEntity() {
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success", Integer.class)).isEqualTo(54);
        long id = order();
        jdbc.update("update orders set status='SHIPPED', shipped_at=localtimestamp where id=?", id);
        var entity = orders.findById(id).orElseThrow();
        assertThat(entity.getShippingPreparationStartedAt()).isNull();
        assertThat(entity.getShippingPreparationStartedByAccountId()).isNull();
        assertThat(entity.getShippingPreparationStartedByUsername()).isNull();
        assertThat(entity.getShippedByAccountId()).isNull();
        assertThat(entity.getShippedByUsername()).isNull();
        assertThat(entity.getShipmentCapturePaymentId()).isNull();
    }

    @Test void entityMappingWithCaptureAndRestrictDelete() {
        long id = order();
        ship(id);
        long payment = jdbc.queryForObject("insert into payments(order_id,provider,payment_method,status,amount) values(?,'PAYJP','CARD','AUTHORIZED',100) returning id", Long.class, id);
        jdbc.update("update orders set shipment_capture_payment_id=? where id=?", payment, id);
        var entity = orders.findById(id).orElseThrow();
        assertThat(entity.getShippingPreparationStartedAt()).isEqualTo(LocalDateTime.of(2026,10,16,10,0));
        assertThat(entity.getShippingPreparationStartedByAccountId()).isEqualTo(7L);
        assertThat(entity.getShippingPreparationStartedByUsername()).isEqualTo("admin");
        assertThat(entity.getShippedByAccountId()).isEqualTo(8L);
        assertThat(entity.getShippedByUsername()).isEqualTo("shipper");
        assertThat(entity.getShipmentCapturePaymentId()).isEqualTo(payment);
        rejects(() -> jdbc.update("delete from payments where id=?", payment),
                "fk_orders_shipment_capture_payment", "23503");
    }

    @Test void manualShipmentAllowsNullCapture() {
        long id = order();
        ship(id);
        assertThat(orders.findById(id).orElseThrow().getShipmentCapturePaymentId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"shipping_preparation_started_at=null", "shipping_preparation_started_by_account_id=null", "shipping_preparation_started_by_username=null", "shipping_preparation_started_by_username=''", "shipping_preparation_started_by_username=E' \\t\\n'"})
    void preparationChecks(String assignment) {
        long id = order(); prepare(id);
        rejects(id, assignment, "chk_orders_shipping_preparation_metadata", "23514");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "shipping_preparation_started_at=localtimestamp",
        "shipping_preparation_started_by_account_id=7",
        "shipping_preparation_started_by_username='admin'"
    })
    void preparationRejectsOnlyOneNonNullField(String assignment) {
        long id = order();
        rejects(id, assignment, "chk_orders_shipping_preparation_metadata", "23514");
    }

    @ParameterizedTest
    @ValueSource(strings = {"shipped_by_account_id=null", "shipped_by_username=null", "shipped_by_username=''", "shipped_by_username=E' \\t\\n'", "status='PAID'", "shipped_at=null", "shipping_preparation_started_at=null,shipping_preparation_started_by_account_id=null,shipping_preparation_started_by_username=null"})
    void shipmentChecks(String assignment) {
        long id = order(); ship(id);
        rejects(id, assignment, "chk_orders_shipment_metadata", "23514");
    }

    @Test void captureWithoutActorRejected() {
        long id = order();
        rejects(id, "shipment_capture_payment_id=9223372036854775807", "chk_orders_shipment_metadata", "23514");
    }

    @Test void captureForeignKey() {
        long id = order(); ship(id);
        rejects(id, "shipment_capture_payment_id=9223372036854775807", "fk_orders_shipment_capture_payment", "23503");
    }

    @Test void partialIndexAndColumnDefinitions() {
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname='public' and indexname='idx_orders_shipment_capture_payment'", String.class))
                .contains("(id)", "WHERE (shipment_capture_payment_id IS NOT NULL)");
        assertThat(jdbc.queryForList("select is_nullable from information_schema.columns where table_schema='public' and table_name='orders' and column_name in ('shipping_preparation_started_at','shipping_preparation_started_by_account_id','shipping_preparation_started_by_username','shipped_by_account_id','shipped_by_username','shipment_capture_payment_id')", String.class))
                .hasSize(6).containsOnly("YES");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='public' and table_name='orders' and column_name in ('shipping_preparation_started_at','shipping_preparation_started_by_account_id','shipping_preparation_started_by_username','shipped_by_account_id','shipped_by_username','shipment_capture_payment_id') and column_default is not null", Integer.class)).isZero();
    }

    @Test void databaseClockUsesTokyoRegardlessOfSessionTimezone() {
        jdbc.execute("set local time zone 'UTC'");
        LocalDateTime before = jdbc.queryForObject("select clock_timestamp() at time zone 'Asia/Tokyo'", LocalDateTime.class);
        LocalDateTime actual = orders.findCurrentTimeInTokyo();
        LocalDateTime after = jdbc.queryForObject("select clock_timestamp() at time zone 'Asia/Tokyo'", LocalDateTime.class);
        assertThat(actual).isBetween(before, after);
    }
}
