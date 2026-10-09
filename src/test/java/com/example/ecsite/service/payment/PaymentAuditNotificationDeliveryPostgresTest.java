package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.service.AdminPaymentDiscrepancyAuditRunService;
import com.example.ecsite.dto.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","spring.sql.init.mode=never",
    "app.payment.discrepancy-audit.notification.enabled=true","app.payment.discrepancy-audit.notification.recipients=Admin@EXAMPLE.com,new@example.com"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentAuditNotificationDeliveryTransaction.class,PaymentAuditNotificationDeliveryService.class,
    PaymentAuditNotificationSmtpExecutor.class,PaymentAuditNotificationObservationService.class,PaymentAuditNotificationObservationTransaction.class,
    PaymentAuditNotificationDeliveryPostgresTest.Config.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
class PaymentAuditNotificationDeliveryPostgresTest {
    @TestConfiguration @EnableConfigurationProperties(PaymentDiscrepancyAuditNotificationProperties.class) static class Config {
        @Bean PaymentDiscrepancyAuditNotificationMailClient client() {return mock(PaymentDiscrepancyAuditNotificationMailClient.class);}
        @Bean AdminPaymentDiscrepancyAuditRunService monitoring() {return mock(AdminPaymentDiscrepancyAuditRunService.class);}
    }
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17")
        .withCommand("postgres","-c","lock_timeout=5000","-c","statement_timeout=10000");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.flyway.url",POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",POSTGRES::getUsername);r.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @Autowired PaymentAuditNotificationDeliveryTransaction delivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentAuditNotificationRepository notifications;
    @Autowired PaymentAuditNotificationAttemptRepository attempts;
    @Autowired PlatformTransactionManager manager;
    @Autowired PaymentAuditNotificationDeliveryService service;
    @Autowired PaymentDiscrepancyAuditNotificationMailClient client;
    @Autowired PaymentAuditNotificationObservationService observer;
    @Autowired AdminPaymentDiscrepancyAuditRunService monitoring;
    @Autowired DataSource dataSource;
    final UUID owner=UUID.randomUUID();
    @BeforeEach void reset() {
        org.mockito.Mockito.reset(client,monitoring);
        when(monitoring.monitoringSummary()).thenAnswer(invocation -> {
            var evaluated=LocalDateTime.ofInstant(Instant.now(),ZoneId.of("Asia/Tokyo"));
            return new AdminPaymentDiscrepancyAuditMonitoringSummary(null,null,0,0,true,evaluated,
                Duration.ofHours(24),List.of(new AdminPaymentDiscrepancyAuditWarning(AdminPaymentDiscrepancyAuditWarning.Type.LATEST_FAILURE,
                    "unused",evaluated,null,PaymentDiscrepancyAuditRunErrorCode.ITEM_FAILURE)));
        });
        jdbc.execute("drop trigger if exists reject_result on payment_audit_notification_attempts");
        jdbc.execute("drop function if exists reject_result()");
        jdbc.update("delete from payment_audit_notification_attempts");jdbc.update("delete from payment_audit_notification_items");
        jdbc.update("delete from payment_audit_notifications");
        jdbc.update("update payment_audit_notification_states set active=false,episode_no=0,first_observed_at=null,last_observed_at=null,resolved_at=null,last_evaluated_at=null,version=0");
    }
    long pending(String... recipients) {
        long id=jdbc.queryForObject("""
            insert into payment_audit_notifications(status,evaluated_at,from_address,recipients,recipient_set_hash,admin_url,
                max_attempts,retry_delay_ms,next_attempt_at,created_at,updated_at)
            values('PENDING',clock_timestamp(),'no-reply@ec-site.local',?::text[],repeat('a',64),
                'http://localhost:8080/admin/payment-discrepancy-audits',3,300000,clock_timestamp(),clock_timestamp(),clock_timestamp()) returning id
            """,Long.class,(Object)recipients);
        addItem(id,"LATEST_FAILURE",1);
        return id;
    }
    void addItem(long id,String type,long episode) {
        jdbc.update("update payment_audit_notification_states set active=true,episode_no=?,first_observed_at=clock_timestamp(),last_observed_at=clock_timestamp(),resolved_at=null where warning_type=?",episode,type);
        jdbc.update("""
            insert into payment_audit_notification_items(notification_id,warning_type,episode_no,item_status,first_observed_at,created_at,warning_count,error_code)
            values(?,?,?,'INCLUDED',clock_timestamp(),clock_timestamp(),2,'ITEM_FAILURE')
            """,id,type,episode);
    }
    void expire(long id) {jdbc.update("update payment_audit_notifications set lease_until=clock_timestamp()-interval '1 second' where id=?",id);}
    void due(long id) {jdbc.update("update payment_audit_notifications set next_attempt_at=clock_timestamp()-interval '1 second' where id=?",id);}
    PaymentAuditNotification row(long id) {return notifications.findById(id).orElseThrow();}
    @Test void successCommitsAttemptBeforeSmtpAndStoresEffectiveRecipientsAndSafeJapaneseContent() {
        long id=pending("Admin@EXAMPLE.com","old@example.com");
        var claim=delivery.claim(owner).orElseThrow();
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.CLAIMED);
        assertThat(row(id).getAttemptCount()).isZero();
        var mail=delivery.prepare(claim).orElseThrow();
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENDING);
        assertThat(row(id).getAttemptCount()).isEqualTo(1);
        assertThat(mail.recipients()).containsExactly("Admin@example.com");
        assertThat(mail.subject()).contains("PAY.JP", "警告");
        assertThat(mail.body()).contains("直近の定期監査失敗","ITEM_FAILURE","件数：2","http://localhost:8080/admin/payment-discrepancy-audits");
        var attempt=attempts.findByNotificationIdOrderByAttemptNoAsc(id).getFirst();
        assertThat(attempt.getResult()).isEqualTo(PaymentAuditNotificationAttemptResult.IN_PROGRESS);
        assertThat(attempt.getRecipients()).containsExactly("Admin@example.com");
        assertThat(delivery.complete(claim,PaymentAuditNotificationAttemptResult.SUCCESS,null)).isTrue();
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENT);
        assertThat(row(id).getClaimToken()).isNull();
    }
    @Test void failureRetriesAfterFiveMinutesAndExhaustsOnThirdAttempt() {
        long id=pending("Admin@example.com");
        for(int i=1;i<=3;i++) {
            var claim=delivery.claim(owner).orElseThrow();delivery.prepare(claim).orElseThrow();
            delivery.complete(claim,PaymentAuditNotificationAttemptResult.FAILURE,PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED);
            assertThat(row(id).getAttemptCount()).isEqualTo(i);
            if(i<3) {
                assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.RETRY_WAIT);
                assertThat(row(id).getNextAttemptAt()).isAfter(Instant.now().plusSeconds(290));
                assertThat(delivery.claim(owner)).isEmpty();due(id);
            }
        }
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.EXHAUSTED);
        assertThat(delivery.claim(owner)).isEmpty();assertThat(attempts.findByNotificationIdOrderByAttemptNoAsc(id)).hasSize(3);
    }
    @Test void expiredClaimDoesNotConsumeAttemptAndRejectsOldToken() {
        long id=pending("Admin@example.com");var old=delivery.claim(owner).orElseThrow();expire(id);
        delivery.recoverExpired();var fresh=delivery.claim(UUID.randomUUID()).orElseThrow();
        assertThat(fresh.token()).isNotEqualTo(old.token());assertThat(row(id).getAttemptCount()).isZero();
        assertThat(delivery.prepare(old)).isEmpty();assertThat(delivery.complete(old,PaymentAuditNotificationAttemptResult.SUCCESS,null)).isFalse();
        assertThat(delivery.prepare(fresh)).isPresent();
        assertThat(delivery.complete(old,PaymentAuditNotificationAttemptResult.SUCCESS,null)).isFalse();
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENDING);
    }
    @Test void expiredSendingIsUnknownCountedOnceAndOldResultsCannotOverwriteRetry() {
        long id=pending("Admin@example.com");var claim=delivery.claim(owner).orElseThrow();delivery.prepare(claim);expire(id);
        assertThat(delivery.complete(claim,PaymentAuditNotificationAttemptResult.SUCCESS,null)).isFalse();
        delivery.recoverExpired();delivery.recoverExpired();
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.RETRY_WAIT);
        assertThat(row(id).getAttemptCount()).isEqualTo(1);assertThat(row(id).isDeliveryUncertain()).isTrue();
        assertThat(attempts.findByNotificationIdOrderByAttemptNoAsc(id).getFirst().getResult()).isEqualTo(PaymentAuditNotificationAttemptResult.UNKNOWN);
        assertThat(delivery.complete(claim,PaymentAuditNotificationAttemptResult.SUCCESS,null)).isFalse();
    }
    @Test void deadlineUnknownIsRetriedAndExhausted() {
        long id=pending("Admin@example.com");
        for(int i=0;i<3;i++) {
            var claim=delivery.claim(owner).orElseThrow();delivery.prepare(claim);
            delivery.complete(claim,PaymentAuditNotificationAttemptResult.UNKNOWN,PaymentAuditNotificationFailureCode.SEND_DEADLINE_EXCEEDED);
            if(i<2)due(id);
        }
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.EXHAUSTED);
        assertThat(attempts.findByNotificationIdOrderByAttemptNoAsc(id)).allSatisfy(a->assertThat(a.getResult()).isEqualTo(PaymentAuditNotificationAttemptResult.UNKNOWN));
    }
    @Test void preflightCancelsAllResolvedWithoutConsumingAttempt() {
        long id=pending("Admin@example.com");var claim=delivery.claim(owner).orElseThrow();
        jdbc.update("update payment_audit_notification_states set active=false,resolved_at=clock_timestamp() where warning_type='LATEST_FAILURE'");
        assertThat(delivery.prepare(claim)).isEmpty();
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.CANCELLED);
        assertThat(row(id).getCloseReason()).isEqualTo(PaymentAuditNotificationCloseReason.RESOLVED);
        assertThat(row(id).getAttemptCount()).isZero();
    }
    @Test void preflightExcludesOldEpisodeButSendsRemainingWarning() {
        long id=pending("Admin@example.com");addItem(id,"LONG_RUNNING",1);var claim=delivery.claim(owner).orElseThrow();
        jdbc.update("update payment_audit_notification_states set episode_no=2 where warning_type='LATEST_FAILURE'");
        var mail=delivery.prepare(claim).orElseThrow();
        assertThat(mail.body()).contains("長時間RUNNING").doesNotContain("直近の定期監査失敗");
        assertThat(jdbc.queryForObject("select item_status from payment_audit_notification_items where notification_id=? and warning_type='LATEST_FAILURE'",String.class,id)).isEqualTo("REMOVED_RESOLVED");
    }
    @Test void noEffectiveRecipientsCancelsWithFixedReasonAndNoSmtpAttempt() {
        long id=pending("old@example.com");var claim=delivery.claim(owner).orElseThrow();
        assertThat(delivery.prepare(claim)).isEmpty();
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.CANCELLED);
        assertThat(row(id).getCloseReason()).isEqualTo(PaymentAuditNotificationCloseReason.NO_VALID_RECIPIENTS);
        assertThat(row(id).getAttemptCount()).isZero();assertThat(attempts.findByNotificationIdOrderByAttemptNoAsc(id)).isEmpty();
    }
    @Test void simultaneousOwnersClaimOnlyOnce() throws Exception {
        pending("Admin@example.com");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> task=()->{start.await();return delivery.claim(UUID.randomUUID()).isPresent();};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {pool.shutdownNow();}
    }
    @Test void claimSkipsRowLockedByAnotherConnection() throws Exception {
        long first=pending("Admin@example.com");long second=pendingSecond();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try {
            var holder=pool.submit(()->new TransactionTemplate(manager).execute(s->{
                jdbc.queryForObject("select id from payment_audit_notifications where id=? for update",Long.class,first);
                locked.countDown();try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return null;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(delivery.claim(owner).orElseThrow().id()).isEqualTo(second);release.countDown();holder.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();}
    }
    long pendingSecond() {
        long id=jdbc.queryForObject("""
            insert into payment_audit_notifications(status,evaluated_at,from_address,recipients,recipient_set_hash,admin_url,max_attempts,retry_delay_ms,next_attempt_at,created_at,updated_at)
            select 'PENDING',evaluated_at,from_address,recipients,recipient_set_hash,admin_url,3,300000,clock_timestamp(),created_at,updated_at from payment_audit_notifications limit 1 returning id
            """,Long.class);
        addItem(id,"LONG_RUNNING",1);return id;
    }
    @Test void databaseResultFailureLeavesSendingForRecovery() {
        long id=pending("Admin@example.com");var claim=delivery.claim(owner).orElseThrow();delivery.prepare(claim);
        jdbc.execute("create function reject_result() returns trigger language plpgsql as $$ begin raise exception 'test'; end $$");
        jdbc.execute("create trigger reject_result before update on payment_audit_notification_attempts for each row execute function reject_result()");
        assertThatThrownBy(()->delivery.complete(claim,PaymentAuditNotificationAttemptResult.SUCCESS,null)).isInstanceOf(RuntimeException.class);
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENDING);
        assertThat(attempts.findByNotificationIdOrderByAttemptNoAsc(id).getFirst().getResult()).isEqualTo(PaymentAuditNotificationAttemptResult.IN_PROGRESS);
    }

    @Test void smtpRunsWithoutTransactionConnectionOrLocksAndObservationCanProceed() throws Exception {
        long id=pending("Admin@example.com");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
            assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENDING);
            assertThat(attempts.findByNotificationIdOrderByAttemptNoAsc(id)).hasSize(1);
            entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return null;
        }).when(client).send(any());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var sending=pool.submit(service::tick);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            // Real Stage2 observation obtains all warning locks while SMTP is still blocked.
            observer.observe();
            new TransactionTemplate(manager).execute(s->{
                jdbc.queryForObject("select id from payment_audit_notifications where id=? for update",Long.class,id);return null;
            });
            assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENDING);
            release.countDown();sending.get(10,TimeUnit.SECONDS);assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENT);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void observationAndPreflightUseSameLockOrderUnderConcurrency() throws Exception {
        long id=pending("Admin@example.com");var claim=delivery.claim(owner).orElseThrow();
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var observing=pool.submit(()->{start.await();observer.observe();return true;});
            var preparing=pool.submit(()->{start.await();return delivery.prepare(claim);});start.countDown();
            assertThat(observing.get(10,TimeUnit.SECONDS)).isTrue();assertThat(preparing.get(10,TimeUnit.SECONDS)).isPresent();
            assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENDING);
        } finally {pool.shutdownNow();}
    }
    @Test void retryRechecksRecipientsAndResolvedWarnings() {
        long id=pending("Admin@example.com");var first=delivery.claim(owner).orElseThrow();delivery.prepare(first);
        delivery.complete(first,PaymentAuditNotificationAttemptResult.FAILURE,PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED);due(id);
        var retry=delivery.claim(owner).orElseThrow();
        jdbc.update("update payment_audit_notification_states set active=false,resolved_at=clock_timestamp() where warning_type='LATEST_FAILURE'");
        assertThat(delivery.prepare(retry)).isEmpty();assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.CANCELLED);
        assertThat(row(id).getAttemptCount()).isEqualTo(1);
    }

    @Test void deliveryNeverExceedsThreeAttemptsEvenForLargerPersistenceBudget() {
        long id=pending("Admin@example.com");
        jdbc.update("update payment_audit_notifications set max_attempts=10 where id=?",id);
        for(int i=0;i<3;i++) {
            var claim=delivery.claim(owner).orElseThrow();delivery.prepare(claim);
            delivery.complete(claim,PaymentAuditNotificationAttemptResult.FAILURE,PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED);
            if(i<2)due(id);
        }
        assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.EXHAUSTED);
        assertThat(delivery.claim(owner)).isEmpty();
    }

    @Test void exhaustedLogIsEmittedOnlyAfterResultCommit() {
        long id=pending("Admin@example.com");
        for(int i=0;i<2;i++) {
            var claim=delivery.claim(owner).orElseThrow();delivery.prepare(claim);
            delivery.complete(claim,PaymentAuditNotificationAttemptResult.FAILURE,PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED);due(id);
        }
        var claim=delivery.claim(owner).orElseThrow();delivery.prepare(claim);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(PaymentAuditNotificationDeliveryTransaction.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();logger.addAppender(appender);
        try {
            jdbc.execute("create function reject_result() returns trigger language plpgsql as $$ begin raise exception 'test'; end $$");
            jdbc.execute("create trigger reject_result before update on payment_audit_notification_attempts for each row execute function reject_result()");
            assertThatThrownBy(()->delivery.complete(claim,PaymentAuditNotificationAttemptResult.FAILURE,PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED)).isInstanceOf(RuntimeException.class);
            assertThat(row(id).getStatus()).isEqualTo(PaymentAuditNotificationStatus.SENDING);
            assertThat(appender.list).noneMatch(e->e.getFormattedMessage().contains("exhausted"));
            jdbc.execute("drop trigger reject_result on payment_audit_notification_attempts");
            assertThat(delivery.complete(claim,PaymentAuditNotificationAttemptResult.FAILURE,PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED)).isTrue();
            assertThat(appender.list).anySatisfy(e->{
                assertThat(e.getFormattedMessage()).contains("exhausted");
                assertThat(e.getKeyValuePairs()).extracting(k->k.key).contains("notificationId","warningTypes","attemptCount","failureCode");
            });
        } finally {logger.detachAppender(appender);appender.stop();}
    }
}
