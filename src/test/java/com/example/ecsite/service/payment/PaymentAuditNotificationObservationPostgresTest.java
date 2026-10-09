package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.config.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.service.AdminPaymentDiscrepancyAuditRunService;

@DataJpaTest(properties = {
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never",
    "app.payment.reconciliation.enabled=false", "app.payment.discrepancy-audit.enabled=false",
    "app.payment.discrepancy-audit.notification.enabled=true",
    "app.payment.discrepancy-audit.notification.recipients=admin@example.com,other@example.com"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentAuditNotificationObservationService.class, PaymentAuditNotificationObservationTransaction.class,
    AdminPaymentDiscrepancyAuditRunService.class, PaymentAuditNotificationObservationPostgresTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class PaymentAuditNotificationObservationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
        .withCommand("postgres", "-c", "lock_timeout=10000", "-c", "statement_timeout=15000")
        .withDatabaseName("feature115_observation").withUsername("feature115").withPassword("feature115-test");
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",POSTGRES::getUsername);r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    static final Instant NOW = PaymentAuditNotificationObservationTest.NOW;
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value = new AtomicReference<>(NOW);
        @Override public Instant instant() {return value.get();}
        @Override public ZoneId getZone() {return ZoneId.of("Asia/Tokyo");}
        @Override public Clock withZone(ZoneId zone) {return Clock.fixed(instant(),zone);}
    }
    @TestConfiguration(proxyBeanMethods=false)
    @EnableConfigurationProperties(PaymentDiscrepancyAuditNotificationProperties.class)
    static class Config {
        @Bean MutableClock clock() {return new MutableClock();}
        // Audit disabled in the slice's scheduler setting, enabled for real Feature114 monitoring rules.
        @Bean PaymentDiscrepancyAuditProperties auditProperties() {return new PaymentDiscrepancyAuditProperties(true,Duration.ofMinutes(5),100);}
        @Bean PaymentDiscrepancyAuditMonitoringProperties monitoringProperties() {
            return new PaymentDiscrepancyAuditMonitoringProperties(Duration.ofMinutes(15),Duration.ofMinutes(10),3,Duration.ofHours(24));
        }
    }
    @Autowired PaymentAuditNotificationObservationService observer;
    @MockitoSpyBean PaymentAuditNotificationObservationTransaction transaction;
    @Autowired PaymentDiscrepancyAuditNotificationProperties properties;
    @Autowired PaymentAuditNotificationStateRepository states;
    @Autowired PaymentAuditNotificationRepository notifications;
    @Autowired PaymentAuditNotificationItemRepository items;
    @Autowired PaymentDiscrepancyAuditRunRepository runs;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean AdminPaymentDiscrepancyAuditRunService monitoring;

    @BeforeEach void resetDatabase() {
        jdbc.execute("drop trigger if exists fail_notification_item on payment_audit_notification_items");
        jdbc.execute("drop function if exists fail_notification_item()");
        jdbc.update("delete from payment_audit_notification_attempts");jdbc.update("delete from payment_audit_notification_items");
        jdbc.update("delete from payment_audit_notifications");jdbc.update("delete from payment_discrepancy_audit_runs");
        jdbc.update("update payment_audit_notification_states set active=false,episode_no=0,first_observed_at=null,last_evaluated_at=null,last_observed_at=null,resolved_at=null,version=0");
        clock.value.set(NOW);
    }
    PaymentDiscrepancyAuditRun run(PaymentDiscrepancyAuditRunStatus status) {
        var r=PaymentDiscrepancyAuditRun.start("test-process",clock.instant().minusSeconds(10));
        if(status!=PaymentDiscrepancyAuditRunStatus.RUNNING) {
            var summary=switch(status) {
                case SUCCESS -> new PaymentDiscrepancyAuditRunSummary(status,0,0,0,0,0,0,10,null);
                case FAILED -> new PaymentDiscrepancyAuditRunSummary(status,null,0,0,0,0,0,10,PaymentDiscrepancyAuditRunErrorCode.CANDIDATE_FETCH_FAILED);
                case PARTIAL_FAILURE -> new PaymentDiscrepancyAuditRunSummary(status,2,1,0,0,0,1,10,PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE);
                default -> throw new IllegalStateException();
            };
            r.finish(clock.instant(),summary);
        }
        return runs.saveAndFlush(r);
    }
    PaymentAuditNotificationState state(PaymentAuditNotificationWarningType type) {return states.findById(type).orElseThrow();}
    long notificationCount() {return notifications.count();}

    @Test void initialNormalUpdatesSixStatesWithoutNotification() {
        run(PaymentDiscrepancyAuditRunStatus.SUCCESS);observer.observe();
        assertThat(notificationCount()).isZero();assertThat(states.findAll()).allSatisfy(s-> {
            assertThat(s.isActive()).isFalse();assertThat(s.getEpisodeNo()).isZero();assertThat(s.getLastEvaluatedAt()).isEqualTo(NOW);
        });
    }
    @Test void failureRunningPartialSuccessAndRecurrenceUseActualMonitoring() {
        run(PaymentDiscrepancyAuditRunStatus.FAILED);observer.observe();
        clock.value.set(NOW.plusSeconds(1));run(PaymentDiscrepancyAuditRunStatus.RUNNING);
        assertThat(monitoring.monitoringSummary().warnings()).noneMatch(w->w.type()==com.example.ecsite.dto.AdminPaymentDiscrepancyAuditWarning.Type.LATEST_FAILURE);
        observer.observe();assertThat(state(PaymentAuditNotificationWarningType.LATEST_FAILURE).isActive()).isTrue();
        clock.value.set(NOW.plusSeconds(2));run(PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE);observer.observe();
        assertThat(notificationCount()).isEqualTo(1);assertThat(state(PaymentAuditNotificationWarningType.LATEST_FAILURE).getEpisodeNo()).isEqualTo(1);
        clock.value.set(NOW.plusSeconds(3));run(PaymentDiscrepancyAuditRunStatus.SUCCESS);observer.observe();
        assertThat(state(PaymentAuditNotificationWarningType.LATEST_FAILURE).isActive()).isFalse();
        assertThat(notifications.findAll().getFirst().getStatus()).isEqualTo(PaymentAuditNotificationStatus.CANCELLED);
        clock.value.set(NOW.plusSeconds(4));run(PaymentDiscrepancyAuditRunStatus.FAILED);observer.observe();
        assertThat(notificationCount()).isEqualTo(2);assertThat(state(PaymentAuditNotificationWarningType.LATEST_FAILURE).getEpisodeNo()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(longs={899,900,901})
    void graceAndRestartKeepDatabaseFirstObservation(long seconds) {
        observer.observe();assertThat(notificationCount()).isZero();
        clock.value.set(NOW.plusSeconds(seconds));
        new PaymentAuditNotificationObservationService(properties,transaction).observe();
        assertThat(state(PaymentAuditNotificationWarningType.NO_HISTORY).getFirstObservedAt()).isEqualTo(NOW);
        assertThat(notificationCount()).isEqualTo(seconds>=900?1:0);
    }
    @Test void historyDuringGraceResolvesWithoutNotification() {
        observer.observe();clock.value.set(NOW.plusSeconds(899));run(PaymentDiscrepancyAuditRunStatus.SUCCESS);observer.observe();
        assertThat(notificationCount()).isZero();assertThat(state(PaymentAuditNotificationWarningType.NO_HISTORY).isActive()).isFalse();
    }
    @Test void aggregatesNewFailuresAndLeavesOnlyNewWarningsInLaterNotification() {
        run(PaymentDiscrepancyAuditRunStatus.FAILED);clock.value.set(NOW.plusSeconds(1));run(PaymentDiscrepancyAuditRunStatus.FAILED);
        clock.value.set(NOW.plusSeconds(2));run(PaymentDiscrepancyAuditRunStatus.FAILED);observer.observe();
        var first=notifications.findAll().getFirst();
        assertThat(items.findByNotificationIdOrderByIdAsc(first.getId())).extracting(i->i.getState().getWarningType())
            .containsExactly(PaymentAuditNotificationWarningType.CONSECUTIVE_FAILURES,PaymentAuditNotificationWarningType.LATEST_FAILURE);
        observer.observe();assertThat(notificationCount()).isEqualTo(1);
        clock.value.set(NOW.plusSeconds(1000));observer.observe(); // delay is new, failures continue
        assertThat(notificationCount()).isEqualTo(2);
        var latest=notifications.findAll().stream().max(java.util.Comparator.comparing(PaymentAuditNotification::getId)).orElseThrow();
        assertThat(items.findByNotificationIdOrderByIdAsc(latest.getId())).extracting(i->i.getState().getWarningType())
            .containsExactly(PaymentAuditNotificationWarningType.DELAYED);
    }
    @Test void partialResolutionThenFullResolutionCancelsRetryWait() {
        run(PaymentDiscrepancyAuditRunStatus.FAILED);clock.value.set(NOW.plusSeconds(1));run(PaymentDiscrepancyAuditRunStatus.FAILED);
        clock.value.set(NOW.plusSeconds(2));run(PaymentDiscrepancyAuditRunStatus.FAILED);observer.observe();
        var n=notifications.findAll().getFirst();
        jdbc.update("update payment_audit_notifications set status='RETRY_WAIT',attempt_count=1 where id=?",n.getId());
        clock.value.set(NOW.plusSeconds(3));run(PaymentDiscrepancyAuditRunStatus.PARTIAL_FAILURE);observer.observe();
        assertThat(notifications.findById(n.getId()).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationStatus.RETRY_WAIT);
        assertThat(items.findByNotificationIdOrderByIdAsc(n.getId())).extracting(PaymentAuditNotificationItem::getItemStatus)
            .containsExactly(PaymentAuditNotificationItemStatus.REMOVED_RESOLVED,PaymentAuditNotificationItemStatus.INCLUDED);
        clock.value.set(NOW.plusSeconds(4));run(PaymentDiscrepancyAuditRunStatus.SUCCESS);observer.observe();
        var cancelled=notifications.findById(n.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(PaymentAuditNotificationStatus.CANCELLED);
        assertThat(cancelled.getNextAttemptAt()).isNull();assertThat(cancelled.getCloseReason()).isEqualTo(PaymentAuditNotificationCloseReason.RESOLVED);
        assertThat(cancelled.getAttemptCount()).isEqualTo(1);
        assertThat(cancelled.getClosedAt()).isEqualTo(NOW.plusSeconds(4));
        var resolvedItems=items.findByNotificationIdOrderByIdAsc(n.getId());
        assertThat(resolvedItems.getFirst().getResolvedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(resolvedItems.getFirst().getRemovedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(resolvedItems.getLast().getResolvedAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(resolvedItems.getLast().getRemovedAt()).isEqualTo(NOW.plusSeconds(4));
    }
    @ParameterizedTest @ValueSource(strings={"CLAIMED","SENDING"})
    void deliveryOwnedNotificationsAreUntouchedUntilReturnedToWaiting(String status) {
        run(PaymentDiscrepancyAuditRunStatus.FAILED);observer.observe();var n=notifications.findAll().getFirst();
        jdbc.update("""
            update payment_audit_notifications set status=?,attempt_count=?,next_attempt_at=null,
            claim_token='00000000-0000-0000-0000-000000000001',claimed_by='00000000-0000-0000-0000-000000000002',lease_until=? where id=?
            """,status,status.equals("SENDING")?1:0,Timestamp.from(NOW.plusSeconds(180)),n.getId());
        clock.value.set(NOW.plusSeconds(1));run(PaymentDiscrepancyAuditRunStatus.SUCCESS);observer.observe();
        var owned=notifications.findById(n.getId()).orElseThrow();
        assertThat(owned.getStatus().name()).isEqualTo(status);
        assertThat(owned.getClaimToken()).isEqualTo(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(owned.getLeaseUntil()).isEqualTo(NOW.plusSeconds(180));
        var ownedItem=items.findByNotificationIdOrderByIdAsc(n.getId()).getFirst();
        assertThat(ownedItem.getResolvedAt()).isNull();assertThat(ownedItem.getRemovedAt()).isNull();
        assertThat(items.findByNotificationIdOrderByIdAsc(n.getId()).getFirst().getItemStatus()).isEqualTo(PaymentAuditNotificationItemStatus.INCLUDED);
        clock.value.set(NOW.plusSeconds(2));run(PaymentDiscrepancyAuditRunStatus.FAILED);observer.observe(); // new episode before old returns
        jdbc.update("update payment_audit_notifications set status='RETRY_WAIT',attempt_count=1,claim_token=null,claimed_by=null,lease_until=null,next_attempt_at=? where id=?",Timestamp.from(NOW),n.getId());
        observer.observe();
        assertThat(notifications.findById(n.getId()).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationStatus.CANCELLED);
        assertThat(notificationCount()).isEqualTo(2);
    }
    @Test void concurrentObserversRetryRealSerializationConflictAndCreateOneNotification() throws Exception {
        run(PaymentDiscrepancyAuditRunStatus.FAILED);
        var executor=Executors.newFixedThreadPool(2);
        try (var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Both observers establish their RR snapshots while blocked behind this row lock.
            try (var statement=connection.createStatement(); var result=statement.executeQuery(
                    "select warning_type from payment_audit_notification_states order by warning_type for update")) {
                while(result.next()) { /* acquire all six locks */ }
            }
            var a=executor.submit(observer::observe);var b=executor.submit(observer::observe);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            int blocked=0;
            while(blocked<2 && System.nanoTime()<deadline) {
                blocked=jdbc.queryForObject("""
                    select count(*) from pg_stat_activity where datname=current_database()
                    and wait_event_type='Lock' and query like '%payment_audit_notification_states%'
                    """,Integer.class);
                if(blocked<2) TimeUnit.MILLISECONDS.sleep(20);
            }
            assertThat(blocked).isEqualTo(2);
            connection.commit();
            a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);
            verify(transaction,atLeast(3)).observe(); // two initial attempts plus a real 40001 retry
            assertThat(notificationCount()).isEqualTo(1);assertThat(items.count()).isEqualTo(1);
            assertThat(state(PaymentAuditNotificationWarningType.LATEST_FAILURE).getEpisodeNo()).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }

    @Test void callerRollbackDoesNotUndoIndependentObservationTransaction() {
        run(PaymentDiscrepancyAuditRunStatus.FAILED);
        new TransactionTemplate(transactionManager).execute(status->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            observer.observe();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            status.setRollbackOnly();return null;
        });
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(state(PaymentAuditNotificationWarningType.LATEST_FAILURE).isActive()).isTrue();
    }
    @Test void evaluationErrorLeavesExistingStateAndNotificationsUntouched() {
        run(PaymentDiscrepancyAuditRunStatus.FAILED);observer.observe();
        var before=state(PaymentAuditNotificationWarningType.LATEST_FAILURE);
        doThrow(new IllegalStateException("safe evaluation failure")).when(monitoring).monitoringSummary();
        assertThatThrownBy(observer::observe).isInstanceOf(IllegalStateException.class);
        assertThat(state(PaymentAuditNotificationWarningType.LATEST_FAILURE).getVersion()).isEqualTo(before.getVersion());
        assertThat(notificationCount()).isEqualTo(1);
    }
    @Test void itemInsertFailureRollsBackStateAndNotificationCreation() {
        run(PaymentDiscrepancyAuditRunStatus.FAILED);
        jdbc.execute("create function fail_notification_item() returns trigger language plpgsql as $$ begin raise exception 'safe test failure'; end $$");
        jdbc.execute("create trigger fail_notification_item before insert on payment_audit_notification_items for each row execute function fail_notification_item()");
        assertThatThrownBy(observer::observe).isInstanceOf(RuntimeException.class);
        assertThat(notificationCount()).isZero();assertThat(items.count()).isZero();
        assertThat(states.findAll()).allSatisfy(s->{assertThat(s.getEpisodeNo()).isZero();assertThat(s.getLastEvaluatedAt()).isNull();});
    }
}
