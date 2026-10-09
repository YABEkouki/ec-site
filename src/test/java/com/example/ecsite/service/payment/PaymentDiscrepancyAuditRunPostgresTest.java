package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import com.example.ecsite.config.TimeConfig;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.PaymentDiscrepancyAuditRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = {
    "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false",
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentDiscrepancyAuditRunRecordingService.class, TimeConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class PaymentDiscrepancyAuditRunPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
        .withCommand("postgres", "-c", "lock_timeout=10000", "-c", "statement_timeout=15000")
        .withDatabaseName("feature114_runs").withUsername("feature114").withPassword("feature114-test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentDiscrepancyAuditRunRecordingService recording;
    @Autowired PaymentDiscrepancyAuditRunRepository runs;
    @Autowired PlatformTransactionManager manager;
    @Autowired Flyway flyway;
    private static final Instant FINISHED = Instant.parse("2026-10-08T05:00:00.123456Z");

    @Test
    void flywayCreatesAuditRunsTable() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'payment_discrepancy_audit_runs'", Integer.class)).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("50");
        assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_name = 'payment_discrepancy_audit_runs' and column_name = 'started_at'", String.class))
            .isEqualTo("timestamp with time zone");
    }

    @Test void recordingCommitsIndependentlyOfOuterRollbackAndReusesInstanceId() {
        Long[] id = new Long[1];
        new TransactionTemplate(manager).execute(status -> {
            id[0] = recording.startRun();
            assertThat(tx(() -> runs.findById(id[0]).orElseThrow().getStatus())).isEqualTo(PaymentDiscrepancyAuditRunStatus.RUNNING);
            recording.finishRun(id[0], FINISHED, success(2));
            status.setRollbackOnly(); return null;
        });
        var run = tx(() -> runs.findById(id[0]).orElseThrow());
        assertThat(run.getStatus()).isEqualTo(PaymentDiscrepancyAuditRunStatus.SUCCESS);
        assertThat(run.getCandidateCount()).isEqualTo(2);
        assertThat(run.getFinishedAt()).isEqualTo(FINISHED);
        assertThat(run.getDurationMs()).isEqualTo(123L);
        var second = tx(() -> runs.findById(recording.startRun()).orElseThrow());
        assertThat(run.getInstanceId()).isEqualTo(second.getInstanceId()).isNotBlank();
        assertThatThrownBy(() -> recording.finishRun(id[0], FINISHED.plusSeconds(1), success(0)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(tx(() -> runs.findById(id[0]).orElseThrow().getCandidateCount())).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "execution_type = 'MANUAL'", "status = 'INVALID'", "candidate_count = -1",
        "success_count = -1", "inconsistent_count = -1", "in_progress_count = -1",
        "skipped_count = -1", "failure_count = -1", "duration_ms = -1",
        "inconsistent_count = 1", "in_progress_count = 1", "success_count = 1",
        "candidate_count = 0, skipped_count = 1", "finished_at = now()", "duration_ms = 1",
        "status = 'FAILED'", "status = 'SUCCESS', finished_at = now(), duration_ms = 0",
        "status = 'SUCCESS', candidate_count = 1, finished_at = now(), duration_ms = 0",
        "status = 'SUCCESS', candidate_count = 1, failure_count = 1, finished_at = now(), duration_ms = 0",
        "status = 'PARTIAL_FAILURE', candidate_count = 1, failure_count = 1, finished_at = now(), duration_ms = 0",
        "status = 'PARTIAL_FAILURE', candidate_count = 1, success_count = 1, finished_at = now(), duration_ms = 0",
        "candidate_count = 1, success_count = 2147483647, skipped_count = 2147483647"
    })
    void databaseRejectsInvalidRowsAndRollbackPreservesRunning(String assignments) {
        Long id = recording.startRun();
        assertThatThrownBy(() -> tx(() -> { jdbc.update("update payment_discrepancy_audit_runs set " + assignments + " where id = ?", id); return null; }))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var row = tx(() -> runs.findById(id).orElseThrow());
        assertThat(row.getStatus()).isEqualTo(PaymentDiscrepancyAuditRunStatus.RUNNING);
        assertThat(row.getCandidateCount()).isNull();
        assertThat(row.getProcessedCount()).isZero();
        assertThat(row.getFinishedAt()).isNull();
    }

    @Test void failedAndPartialResultsCommitWithSafeSummaries() {
        for (var summary : List.of(
            new PaymentDiscrepancyAuditRunSummary(PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE, 2, 1, 1, 0, 0, 1, 123, PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE),
            new PaymentDiscrepancyAuditRunSummary(PaymentDiscrepancyAuditRunStatus.FAILED, null, 0, 0, 0, 0, 0, 123, PaymentDiscrepancyAuditRunErrorCode.CANDIDATE_FETCH_FAILED),
            new PaymentDiscrepancyAuditRunSummary(PaymentDiscrepancyAuditRunStatus.FAILED, 3, 1, 0, 0, 0, 0, 123, PaymentDiscrepancyAuditRunErrorCode.EXECUTION_ABORTED))) {
            Long id = recording.startRun(); recording.finishRun(id,FINISHED,summary);
            var row = tx(() -> runs.findById(id).orElseThrow());
            assertThat(row.getStatus()).isEqualTo(summary.status());
            assertThat(row.getUnprocessedCount()).isEqualTo(summary.unprocessedCount());
            assertThat(row.getErrorSummary()).isEqualTo(summary.errorCode().summary());
        }
    }

    @Test void databaseSaveFailuresPropagateAndLeaveNoPartialFinish() {
        Long id = recording.startRun();
        jdbc.execute("create function feature114_reject_write() returns trigger language plpgsql as $$ begin raise exception 'test write rejection'; end $$");
        try {
            jdbc.execute("create trigger feature114_reject_write before insert or update on payment_discrepancy_audit_runs for each row execute function feature114_reject_write()");
            long before = runs.count();
            assertThatThrownBy(recording::startRun).isInstanceOf(RuntimeException.class);
            assertThat(runs.count()).isEqualTo(before);
            assertThatThrownBy(() -> recording.finishRun(id, FINISHED, success(1))).isInstanceOf(RuntimeException.class);
            assertThat(tx(() -> runs.findById(id).orElseThrow().getStatus())).isEqualTo(PaymentDiscrepancyAuditRunStatus.RUNNING);
        } finally {
            jdbc.execute("drop trigger if exists feature114_reject_write on payment_discrepancy_audit_runs");
            jdbc.execute("drop function feature114_reject_write()");
        }
        recording.finishRun(id, FINISHED, success(1));
    }

    @Test void concurrentFinishWaitsForRealRowLockAndOnlyOneResultCommits() throws Exception {
        Long id = recording.startRun();
        var workers = Executors.newFixedThreadPool(3);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pid = new java.util.concurrent.atomic.AtomicInteger();
        try {
            Future<?> blocker = workers.submit(() -> tx(() -> {
                runs.findForFinish(id).orElseThrow();
                pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                locked.countDown();
                try { assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return null;
            }));
            assertThat(locked.await(15,TimeUnit.SECONDS)).isTrue();
            Future<Throwable> first = workers.submit(() -> finishOutcome(id,1));
            Future<Throwable> second = workers.submit(() -> finishOutcome(id,2));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            boolean waited = false;
            var interval = new CountDownLatch(1);
            while (System.nanoTime() < deadline) {
                Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where pid <> pg_backend_pid() and wait_event_type = 'Lock' and cardinality(pg_blocking_pids(pid)) > 0 and query like '%payment_discrepancy_audit_runs%'",Integer.class);
                if (waiting >= 2) { waited = true; break; }
                interval.await(25,TimeUnit.MILLISECONDS);
            }
            assertThat(waited).as("both finish transactions wait on real row locks").isTrue();
            release.countDown(); blocker.get(15,TimeUnit.SECONDS);
            Throwable a=first.get(15,TimeUnit.SECONDS), b=second.get(15,TimeUnit.SECONDS);
            assertThat((a==null) != (b==null)).isTrue();
            assertThat(a==null ? b:a).isInstanceOf(IllegalStateException.class);
            var row = tx(() -> runs.findById(id).orElseThrow());
            assertThat(row.getStatus()).isEqualTo(PaymentDiscrepancyAuditRunStatus.SUCCESS);
            assertThat(row.getCandidateCount()).isEqualTo(a==null ? 1:2);
        } finally {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(20,TimeUnit.SECONDS)).isTrue();
        }
    }
    private Throwable finishOutcome(Long id,int count) {
        try { recording.finishRun(id,FINISHED,success(count)); return null; }
        catch (RuntimeException e) { return e; }
    }
    private PaymentDiscrepancyAuditRunSummary success(int count) {
        return new PaymentDiscrepancyAuditRunSummary(PaymentDiscrepancyAuditRunStatus.SUCCESS,count,count,0,0,0,0,123,null);
    }
    private <T> T tx(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }
}
