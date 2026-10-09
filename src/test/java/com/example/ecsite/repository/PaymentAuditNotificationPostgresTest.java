package com.example.ecsite.repository;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import org.springframework.dao.DataIntegrityViolationException;




import jakarta.persistence.EntityManager;
import com.example.ecsite.entity.*;
import com.example.ecsite.dto.AdminPaymentDiscrepancyAuditWarning;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = {
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never",
    "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false",
    "app.payment.discrepancy-audit.notification.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PaymentAuditNotificationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
        .withDatabaseName("feature115").withUsername("feature115").withPassword("feature115-test");

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
    @Autowired EntityManager entityManager;
    @Autowired PaymentAuditNotificationStateRepository states;
    @Autowired PaymentAuditNotificationRepository notifications;
    @Autowired PaymentAuditNotificationItemRepository items;
    @Autowired PaymentAuditNotificationAttemptRepository attempts;
    private static final Instant NOW = Instant.parse("2026-10-09T01:00:00Z");

    private PaymentAuditNotification newNotification() {
        var n = new PaymentAuditNotification();
        n.setStatus(PaymentAuditNotificationStatus.PENDING);
        n.setEvaluatedAt(NOW);
        n.setFromAddress("no-reply@ec-site.local");
        n.setRecipients(new String[]{"admin@example.com", "other@example.com"});
        n.setRecipientSetHash("a".repeat(64));
        n.setAdminUrl("http://localhost:8080/admin/payment-discrepancy-audits");
        n.setMaxAttempts(3);
        n.setRetryDelayMs(300000);
        n.setNextAttemptAt(NOW);
        n.setCreatedAt(NOW);
        n.setUpdatedAt(NOW);
        return n;
    }

    private PaymentAuditNotificationItem newItem(PaymentAuditNotification n, long episode) {
        var i = new PaymentAuditNotificationItem();
        i.setNotification(n);
        i.setState(states.findById(PaymentAuditNotificationWarningType.LATEST_FAILURE).orElseThrow());
        i.setEpisodeNo(episode);
        i.setItemStatus(PaymentAuditNotificationItemStatus.INCLUDED);
        i.setFirstObservedAt(NOW);
        i.setRelatedAt(NOW.minusSeconds(60));
        i.setErrorCode(PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE);
        i.setCreatedAt(NOW);
        return i;
    }

    private PaymentAuditNotificationAttempt newAttempt(PaymentAuditNotification n, int number) {
        var a = new PaymentAuditNotificationAttempt();
        a.setNotification(n);
        a.setAttemptNo(number);
        a.setClaimToken(UUID.randomUUID());
        a.setResult(PaymentAuditNotificationAttemptResult.UNKNOWN);
        a.setStartedAt(NOW);
        a.setFinishedAt(NOW.plusSeconds(120));
        a.setFailureCode(PaymentAuditNotificationFailureCode.SEND_DEADLINE_EXCEEDED);
        a.setSubject("決済監査警告");
        a.setBody("初回観測日時と関連日時を区別する安全な本文");
        // Snapshot retains two recipients, this attempt excludes the removed recipient.
        a.setRecipients(new String[]{"admin@example.com"});
        return a;
    }

    @Test void migrationCreatesFourTablesAndSixInactiveWarningStates() {
        assertThat(jdbc.queryForObject("""
            select count(*) from information_schema.tables where table_schema='public'
            and table_name in ('payment_audit_notification_states', 'payment_audit_notifications',
                'payment_audit_notification_items', 'payment_audit_notification_attempts')
            """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_states where not active and episode_no=0", Integer.class))
            .isEqualTo(6);
    }

    @Test void warningTypesMatchFeature114AndSeedRows() {
        assertThat(Arrays.stream(PaymentAuditNotificationWarningType.values()).map(Enum::name).toList())
            .containsExactlyElementsOf(Arrays.stream(AdminPaymentDiscrepancyAuditWarning.Type.values()).map(Enum::name).toList());
        assertThat(states.findAll()).extracting(PaymentAuditNotificationState::getWarningType)
            .containsExactlyInAnyOrder(PaymentAuditNotificationWarningType.values());
    }

    @Test void repositoriesRoundTripSnapshotsItemsUnknownResultsAndLease() {
        var n = newNotification();
        UUID token = UUID.randomUUID();
        n.setStatus(PaymentAuditNotificationStatus.SENDING);
        n.setAttemptCount(1);
        n.setNextAttemptAt(null);
        n.setClaimToken(token);
        n.setClaimedBy(UUID.randomUUID());
        n.setLeaseUntil(NOW.plusSeconds(180));
        n.setDeliveryUncertain(true);
        notifications.saveAndFlush(n);
        var state = states.findById(PaymentAuditNotificationWarningType.LATEST_FAILURE).orElseThrow();
        state.setActive(true);
        state.setEpisodeNo(1);
        state.setFirstObservedAt(NOW);
        state.setLastObservedAt(NOW);
        state.setLastEvaluatedAt(NOW);
        states.flush();
        items.saveAndFlush(newItem(n, 1));
        var attempt = newAttempt(n, 1);
        attempt.setClaimToken(token);
        attempts.saveAndFlush(attempt);
        entityManager.clear();
        var loaded = notifications.findById(n.getId()).orElseThrow();
        assertThat(loaded.getRecipients()).containsExactly("admin@example.com", "other@example.com");
        assertThat(loaded.getClaimToken()).isEqualTo(token);
        assertThat(loaded.getLeaseUntil()).isEqualTo(NOW.plusSeconds(180));
        assertThat(loaded.isDeliveryUncertain()).isTrue();
        String[] returned = loaded.getRecipients();
        returned[0] = "changed@example.com";
        assertThat(loaded.getRecipients()[0]).isEqualTo("admin@example.com");
        assertThat(items.findByNotificationIdOrderByIdAsc(n.getId())).hasSize(1);
        var item = items.findByStateWarningTypeAndEpisodeNo(PaymentAuditNotificationWarningType.LATEST_FAILURE, 1).orElseThrow();
        assertThat(item.getRelatedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(item.getFirstObservedAt()).isEqualTo(NOW);
        var persistedAttempt = attempts.findByNotificationIdOrderByAttemptNoAsc(n.getId()).getFirst();
        assertThat(persistedAttempt.getResult()).isEqualTo(PaymentAuditNotificationAttemptResult.UNKNOWN);
        assertThat(persistedAttempt.getFailureCode()).isEqualTo(PaymentAuditNotificationFailureCode.SEND_DEADLINE_EXCEEDED);
        assertThat(persistedAttempt.getRecipients()).containsExactly("admin@example.com");
        assertThat(persistedAttempt.getSubject()).isEqualTo("決済監査警告");
        assertThat(states.findById(PaymentAuditNotificationWarningType.LATEST_FAILURE).orElseThrow().getVersion()).isPositive();
    }

    @Test void sameWarningEpisodeCannotBelongToDifferentNotifications() {
        var first = notifications.saveAndFlush(newNotification());
        items.saveAndFlush(newItem(first, 1));
        var second = notifications.saveAndFlush(newNotification());
        assertThatThrownBy(() -> items.saveAndFlush(newItem(second, 1)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_payment_audit_notification_items_episode");
    }

    @Test void recurrenceAndMultipleWarningsCanBePersisted() {
        var n = notifications.saveAndFlush(newNotification());
        items.saveAndFlush(newItem(n, 1));
        items.saveAndFlush(newItem(n, 2));
        var other = newItem(n, 1);
        other.setState(states.findById(PaymentAuditNotificationWarningType.CONSECUTIVE_FAILURES).orElseThrow());
        other.setWarningCount(3L);
        items.saveAndFlush(other);
        assertThat(items.findByNotificationIdOrderByIdAsc(n.getId())).hasSize(3);
    }

    @Test void duplicateAttemptNumberIsRejected() {
        var n = notifications.saveAndFlush(newNotification());
        attempts.saveAndFlush(newAttempt(n, 1));
        assertThatThrownBy(() -> attempts.saveAndFlush(newAttempt(n, 1)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_payment_audit_notification_attempts_number");
    }

    @Test void claimTokenCannotIdentifyTwoAttempts() {
        var n = notifications.saveAndFlush(newNotification());
        var first = attempts.saveAndFlush(newAttempt(n, 1));
        var second = newAttempt(n, 2);
        second.setClaimToken(first.getClaimToken());
        assertThatThrownBy(() -> attempts.saveAndFlush(second))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_payment_audit_notification_attempts_token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"episode_no=-1", "warning_type='INVALID'", "active=true", "episode_no=1",
        "version=-1"})
    void stateConstraintsRejectInvalidRows(String assignment) {
        assertThatThrownBy(() -> jdbc.update("update payment_audit_notification_states set " + assignment + " where warning_type='LATEST_FAILURE'"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status='INVALID'", "status='CLAIMED'", "status='SENT'", "status='EXHAUSTED'",
        "attempt_count=-1", "attempt_count=4", "max_attempts=0", "max_attempts=11", "retry_delay_ms=0",
        "claim_token='00000000-0000-0000-0000-000000000001'", "recipients=ARRAY[]::text[]",
        "recipients=ARRAY[NULL]::text[]", "recipients=ARRAY['']::text[]", "recipients=array_fill('a@example.com'::text, ARRAY[21])",
        "recipient_set_hash='invalid'", "next_attempt_at=NULL", "close_reason='RESOLVED'"})
    void notificationConstraintsRejectInvalidRows(String assignment) {
        var n = notifications.saveAndFlush(newNotification());
        assertThatThrownBy(() -> jdbc.update("update payment_audit_notifications set " + assignment + " where id=?", n.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"episode_no=0", "episode_no=-1", "warning_type='INVALID'", "notification_id=-1",
        "item_status='INVALID'", "item_status='REMOVED_RESOLVED'", "warning_count=-1", "error_code='exception text'"})
    void itemConstraintsRejectInvalidRows(String assignment) {
        var n = notifications.saveAndFlush(newNotification());
        var i = items.saveAndFlush(newItem(n, 1));
        assertThatThrownBy(() -> jdbc.update("update payment_audit_notification_items set " + assignment + " where id=?", i.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt_no=0", "attempt_no=11", "result='INVALID'", "finished_at=NULL",
        "failure_code=NULL", "failure_code='exception text'", "result='SUCCESS'", "result='IN_PROGRESS'",
        "notification_id=-1", "recipients=ARRAY[]::text[]"})
    void attemptConstraintsRejectInvalidRows(String assignment) {
        var n = notifications.saveAndFlush(newNotification());
        var a = attempts.saveAndFlush(newAttempt(n, 1));
        assertThatThrownBy(() -> jdbc.update("update payment_audit_notification_attempts set " + assignment + " where id=?", a.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void deletingReferencedNotificationIsRejected() {
        var n = notifications.saveAndFlush(newNotification());
        items.saveAndFlush(newItem(n, 1));
        assertThatThrownBy(() -> jdbc.update("delete from payment_audit_notifications where id=?", n.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void partialIndexesExistForPendingLeaseAndHistoryQueries() {
        assertThat(jdbc.queryForList("select indexname from pg_indexes where tablename='payment_audit_notifications'", String.class))
            .contains("idx_payment_audit_notifications_pending", "idx_payment_audit_notifications_lease", "idx_payment_audit_notifications_created");
    }

    @Test void migratesFromV49WithoutChangingAuditHistory() {
        String schema = "feature115_upgrade";
        var old = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).target("49").load();
        old.migrate();
        jdbc.update("insert into " + schema + ".payment_discrepancy_audit_runs (execution_type,status,started_at,instance_id) values ('SCHEDULED','RUNNING',now(),'existing-process')");
        var upgrade = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).load();
        upgrade.migrate();
        upgrade.validate();
        assertThat(upgrade.info().current().getVersion().toString()).isEqualTo("50");
        assertThat(jdbc.queryForObject("select count(*) from " + schema + ".payment_discrepancy_audit_runs where instance_id='existing-process' and status='RUNNING'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from " + schema + ".payment_audit_notification_states", Integer.class)).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CLAIMED", "SENDING", "RETRY_WAIT", "SENT", "EXHAUSTED", "CANCELLED"})
    void validDeliveryStatesRoundTrip(String statusName) {
        var n = newNotification();
        var status = PaymentAuditNotificationStatus.valueOf(statusName);
        n.setStatus(status);
        if (status != PaymentAuditNotificationStatus.PENDING && status != PaymentAuditNotificationStatus.RETRY_WAIT)
            n.setNextAttemptAt(null);
        if (status == PaymentAuditNotificationStatus.CLAIMED || status == PaymentAuditNotificationStatus.SENDING) {
            n.setClaimToken(UUID.randomUUID());
            n.setClaimedBy(UUID.randomUUID());
            n.setLeaseUntil(NOW.plusSeconds(180));
        }
        if (status == PaymentAuditNotificationStatus.SENDING || status == PaymentAuditNotificationStatus.RETRY_WAIT
                || status == PaymentAuditNotificationStatus.SENT) n.setAttemptCount(1);
        if (status == PaymentAuditNotificationStatus.SENT) n.setSentAt(NOW);
        if (status == PaymentAuditNotificationStatus.EXHAUSTED) {
            n.setAttemptCount(3);
            n.setCloseReason(PaymentAuditNotificationCloseReason.MAX_ATTEMPTS);
        }
        if (status == PaymentAuditNotificationStatus.CANCELLED)
            n.setCloseReason(PaymentAuditNotificationCloseReason.NO_VALID_RECIPIENTS);
        if (status == PaymentAuditNotificationStatus.SENT || status == PaymentAuditNotificationStatus.EXHAUSTED
                || status == PaymentAuditNotificationStatus.CANCELLED) n.setClosedAt(NOW);
        notifications.saveAndFlush(n);
        entityManager.clear();
        assertThat(notifications.findById(n.getId()).orElseThrow().getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "SUCCESS", "FAILURE", "UNKNOWN"})
    void validAttemptResultsRoundTrip(String resultName) {
        var n = notifications.saveAndFlush(newNotification());
        var a = newAttempt(n, 1);
        var result = PaymentAuditNotificationAttemptResult.valueOf(resultName);
        a.setResult(result);
        if (result == PaymentAuditNotificationAttemptResult.IN_PROGRESS) a.setFinishedAt(null);
        if (result == PaymentAuditNotificationAttemptResult.IN_PROGRESS || result == PaymentAuditNotificationAttemptResult.SUCCESS)
            a.setFailureCode(null);
        String[] recipients = {"admin@example.com"};
        a.setRecipients(recipients);
        recipients[0] = "mutated@example.com";
        assertThat(a.getRecipients()).containsExactly("admin@example.com");
        a.getRecipients()[0] = "mutated-again@example.com";
        assertThat(a.getRecipients()).containsExactly("admin@example.com");
        attempts.saveAndFlush(a);
        entityManager.clear();
        assertThat(attempts.findById(a.getId()).orElseThrow().getResult()).isEqualTo(result);
    }

    @Test void stateVersionRejectsStaleUpdates() {
        var stale = states.findById(PaymentAuditNotificationWarningType.NO_HISTORY).orElseThrow();
        entityManager.detach(stale);
        var current = states.findById(PaymentAuditNotificationWarningType.NO_HISTORY).orElseThrow();
        current.setLastEvaluatedAt(NOW);
        states.flush();
        stale.setLastEvaluatedAt(NOW.plusSeconds(1));
        assertThatThrownBy(() -> states.saveAndFlush(stale))
            .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }
}
