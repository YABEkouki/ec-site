package com.example.ecsite.service.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.ecsite.entity.PaymentDiscrepancyAuditRun;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunStatus;
import com.example.ecsite.entity.PaymentDiscrepancyAuditRunErrorCode;
import com.example.ecsite.service.payment.PaymentDiscrepancyAuditRunSummary;
import com.example.ecsite.repository.PaymentDiscrepancyAuditRunRepository;

/** Feature145-B: every status query runs against the migrated schema (V113) and only reads. */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never",
        "app.warehouse-export.start-date=2026-12-01", "payjp.capture-connection.mode=TEST"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OperationsStatusService.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperationsStatusPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("feature145_operations").withUsername("test").withPassword("test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword); r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername); r.add("spring.flyway.password", POSTGRES::getPassword);
    }
    @Autowired OperationsStatusService service;
    @Autowired PaymentDiscrepancyAuditRunRepository runs;
    @Autowired JdbcTemplate jdbc;

    @Test void readsEveryProcessFromTheSchema() {
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success", Integer.class)).isEqualTo(114);
        var s = service.read();
        assertThat(s.enabling()).hasSize(8).allSatisfy(p -> {
            assertThat(p.enabled()).isFalse();
            assertThat(p.lastActivity()).isNull();
            assertThat(p.backlog()).allSatisfy(c -> assertThat(c.count()).isZero());
        });
        assertThat(s.enabling().get(5).settings()).anySatisfy(x -> assertThat(x.value()).isEqualTo("2026-12-01"));
        assertThat(s.alwaysOn()).hasSize(2);
        assertThat(s.connection().mode()).isEqualTo("TEST");
        assertThat(s.warnings()).anySatisfy(w -> assertThat(w).contains("LIVE ではありません"));
        // A fresh database has never approved the Feature125 runtime.
        if (s.runtime().readable()) assertThat(s.runtime().available()).isFalse();
        assertThat(s.readAt()).isNotNull();
    }

    @Test void showsTheLatestAuditRunAndRecentFailures() {
        Instant started = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        var run = PaymentDiscrepancyAuditRun.start("node", started);
        run.finish(started.plusSeconds(1), new PaymentDiscrepancyAuditRunSummary(
                PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE, 2, 1, 0, 0, 0, 1, 1000, PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE));
        runs.saveAndFlush(run);
        var audit = service.read().alwaysOn().get(1);
        assertThat(audit.lastActivity()).isEqualTo(started.atZone(java.time.ZoneId.of("Asia/Tokyo")).toLocalDateTime());
        assertThat(audit.backlog()).singleElement().satisfies(c -> assertThat(c.count()).isOne());
    }
}
