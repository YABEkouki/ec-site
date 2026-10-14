package com.example.ecsite.repository;

import java.sql.Timestamp;
import java.util.UUID;
import javax.sql.DataSource;
import com.example.ecsite.entity.*;
import com.example.ecsite.support.PaymentAuditNotificationHistoryFixtures;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false","app.payment.discrepancy-audit.notification.enabled=false","app.payment.discrepancy-audit.enabled=false","app.payment.reconciliation.enabled=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@org.springframework.context.annotation.Import({com.example.ecsite.service.payment.PaymentAuditNotificationManualResendDeliveryTransaction.class,com.example.ecsite.service.payment.PaymentAuditNotificationManualResendDeliveryService.class,PaymentAuditNotificationManualResendDeliveryPostgresTest.Config.class})
@Testcontainers
class PaymentAuditNotificationManualResendDeliveryPostgresTest {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17").withDatabaseName("feature118_v52_isolated");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl); r.add("spring.datasource.username", DB::getUsername); r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.flyway.url", DB::getJdbcUrl); r.add("spring.flyway.user", DB::getUsername); r.add("spring.flyway.password", DB::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    @Autowired PaymentAuditNotificationManualResendRepository requests;
    @Autowired PaymentAuditNotificationManualResendAttemptRepository attempts;
    @Autowired PaymentAuditNotificationHandlingHistoryManualUnknownRepository unknowns;
    @org.springframework.boot.test.context.TestConfiguration static class Config {
        @org.springframework.context.annotation.Bean com.example.ecsite.service.PaymentAuditNotificationManualResendToken manualToken(){return new com.example.ecsite.service.PaymentAuditNotificationManualResendToken(java.util.Base64.getEncoder().encodeToString(new byte[32]),java.time.Clock.systemUTC());}
        @org.springframework.context.annotation.Bean com.example.ecsite.service.AdminPaymentAuditNotificationManualResendService acceptance(PaymentAuditNotificationRepository n,PaymentAuditNotificationManualResendRepository r,PaymentAuditNotificationManualResendAttemptRepository a,PaymentAuditNotificationHandlingRepository h,com.example.ecsite.service.PaymentAuditNotificationManualResendToken t,JdbcTemplate j){return new com.example.ecsite.service.AdminPaymentAuditNotificationManualResendService(n,r,a,h,t,j,true);}
        @org.springframework.context.annotation.Bean com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties properties(){return org.mockito.Mockito.mock(com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties.class);}
        @org.springframework.context.annotation.Bean com.example.ecsite.service.payment.PaymentDiscrepancyAuditNotificationMailClient client(){return org.mockito.Mockito.mock(com.example.ecsite.service.payment.PaymentDiscrepancyAuditNotificationMailClient.class);}
        @org.springframework.context.annotation.Bean com.example.ecsite.service.payment.PaymentAuditNotificationSmtpExecutor executor(com.example.ecsite.service.payment.PaymentDiscrepancyAuditNotificationMailClient c){return new com.example.ecsite.service.payment.PaymentAuditNotificationSmtpExecutor(c);}
    }
    @Autowired com.example.ecsite.service.payment.PaymentAuditNotificationManualResendDeliveryTransaction delivery;
    @Autowired com.example.ecsite.service.payment.PaymentAuditNotificationManualResendDeliveryService service;
    @Autowired com.example.ecsite.service.payment.PaymentDiscrepancyAuditNotificationMailClient client;
    @Autowired com.example.ecsite.service.payment.PaymentAuditNotificationSmtpExecutor smtp;
    @Autowired com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties properties;
    @Autowired com.example.ecsite.service.AdminPaymentAuditNotificationManualResendService acceptance;
    PaymentAuditNotificationHistoryFixtures fixtures;
    void tx(Runnable operation) { new TransactionTemplate(manager).executeWithoutResult(s -> operation.run()); }
    @BeforeEach void setup() throws Exception {
        try (var c = source.getConnection()) { assertThat(c.getMetaData().getURL()).isEqualTo(DB.getJdbcUrl()); }
        assertThat(DB.getMappedPort(5432)).isNotEqualTo(5432);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("feature118_v52_isolated");
        org.mockito.Mockito.reset(client,properties);
        org.mockito.Mockito.when(properties.enabled()).thenReturn(true);
        org.mockito.Mockito.when(properties.recipients()).thenReturn(java.util.List.of("hidden-first@example.com"));
        org.mockito.Mockito.when(properties.claimLease()).thenReturn(java.time.Duration.ofMinutes(3));
        var settings=org.mockito.Mockito.mock(com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties.Smtp.class);
        org.mockito.Mockito.when(settings.sendDeadline()).thenReturn(java.time.Duration.ofMillis(100));
        org.mockito.Mockito.when(properties.smtp()).thenReturn(settings);
        fixtures = new PaymentAuditNotificationHistoryFixtures(jdbc);
        tx(() -> {
            jdbc.update("delete from payment_audit_notification_handling_history_manual_unknowns");
            jdbc.update("delete from payment_audit_notification_manual_resend_attempts");
            jdbc.update("delete from payment_audit_notification_manual_resends");
            fixtures.clear();
            jdbc.update("update payment_audit_notification_states set active=false,episode_no=0,first_observed_at=null,last_observed_at=null,resolved_at=null,last_evaluated_at=null,version=0");
        });
    }
    long notification() { return fixtures.notification("EXHAUSTED", PaymentAuditNotificationHistoryFixtures.NOW); }
    Timestamp time(int seconds) { return Timestamp.from(PaymentAuditNotificationHistoryFixtures.NOW.plusSeconds(seconds)); }
    String code(String status) { return switch(status) { case "FAILED", "FAILURE" -> "SMTP_SEND_FAILED"; case "UNKNOWN" -> "CLAIM_LOST"; case "CANCELLED" -> "RESOLVED"; default -> null; }; }
    long request(long n, int number, UUID nonce, String status, UUID token) {
        boolean claimed = status.equals("CLAIMED") || status.equals("SENDING");
        boolean started = java.util.Set.of("SENDING","SENT","FAILED","UNKNOWN").contains(status);
        boolean finished = java.util.Set.of("SENT","FAILED","UNKNOWN","CANCELLED").contains(status);
        return jdbc.queryForObject("""
            insert into payment_audit_notification_manual_resends(notification_id,request_no,request_nonce,actor_user_id,actor_username,reason,status,result_reason_code,claim_token,claimed_by,lease_until,requested_at,started_at,finished_at)
            values(?,?,?,7,'fixture-admin','再送検証',?,?,?,?,?,?,?,?) returning id
            """, Long.class, n, number, nonce, status, code(status), claimed ? token : null, claimed ? UUID.randomUUID() : null,
            claimed ? time(180) : null, time(0), started ? time(1) : null, finished ? time(2) : null);
    }
    long attempt(long request, long n, String result, UUID token) {
        return jdbc.queryForObject("""
            insert into payment_audit_notification_manual_resend_attempts(manual_resend_id,notification_id,result,claim_token,started_at,finished_at,result_reason_code,recipients,subject,body)
            values(?,?,?,?,?,?,?,ARRAY['fixture@example.invalid'],'検証','検証本文') returning id
            """, Long.class, request, n, result, token, time(1), result.equals("IN_PROGRESS") ? null : time(2), code(result));
    }
    long history(long n, String status) {
        return jdbc.queryForObject("""
            insert into payment_audit_notification_handling_histories(notification_id,from_status,to_status,actor_user_id,actor_username,comment,event_id,changed_at)
            values(?,'UNREVIEWED',?,7,'fixture-admin','確認',?,?) returning id
            """, Long.class, n, status, UUID.randomUUID(), time(3));
    }
    void snapshot(long h, long a, long n) { jdbc.update("insert into payment_audit_notification_handling_history_manual_unknowns(history_id,manual_attempt_id,notification_id) values(?,?,?)", h,a,n); }
    void rejects(Runnable operation) {
        Throwable failure = catchThrowable(() -> tx(operation));
        assertThat(failure).isNotNull();
        while (!(failure instanceof java.sql.SQLException) && failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException) failure).getSQLState()).isIn("23514", "23505", "23503", "22001");
    }

    long pending(){long n=notification();fixtures.item(n,"LATEST_FAILURE",false);jdbc.update("update payment_audit_notification_states set active=true,episode_no=?,first_observed_at=clock_timestamp(),last_observed_at=clock_timestamp() where warning_type='LATEST_FAILURE'",n);final long[] r=new long[1];tx(()->r[0]=request(n,1,UUID.randomUUID(),"PENDING",UUID.randomUUID()));return r[0];}
    void expire(long id){jdbc.update("update payment_audit_notification_manual_resends set lease_until=clock_timestamp()-interval '1 second' where id=?",id);}
    @AfterEach void idle() throws Exception {var occupied=(java.util.concurrent.atomic.AtomicBoolean)org.springframework.test.util.ReflectionTestUtils.getField(smtp,"occupied");long limit=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(occupied.get() && System.nanoTime()<limit)Thread.sleep(10);assertThat(occupied.get()).isFalse();}
    @ParameterizedTest @ValueSource(strings={"SUCCESS","FAILURE","UNKNOWN"}) void outcomes(String outcome){
        long r=pending();long n=requests.findById(r).orElseThrow().getNotificationId();String parent=jdbc.queryForObject("select row_to_json(n)::text from payment_audit_notifications n where id=?",String.class,n);String item=jdbc.queryForObject("select row_to_json(i)::text from payment_audit_notification_items i where notification_id=?",String.class,n);
        if(outcome.equals("FAILURE"))org.mockito.Mockito.doThrow(new IllegalStateException("synthetic")).when(client).send(org.mockito.ArgumentMatchers.any());
        if(outcome.equals("UNKNOWN"))org.mockito.Mockito.doThrow(new AssertionError("synthetic")).when(client).send(org.mockito.ArgumentMatchers.any());
        service.deliverOne();var a=attempts.findByManualResendId(r).orElseThrow();assertThat(a.getResult().name()).isEqualTo(outcome);assertThat(requests.findById(r).orElseThrow().getStatus().name()).isEqualTo(switch(outcome){case "SUCCESS"->"SENT";case "FAILURE"->"FAILED";default->"UNKNOWN";});assertThat(a.getRecipients()).containsExactly("hidden-first@example.com");
        service.deliverOne();org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any());assertThat(jdbc.queryForObject("select row_to_json(n)::text from payment_audit_notifications n where id=?",String.class,n)).isEqualTo(parent);assertThat(jdbc.queryForObject("select row_to_json(i)::text from payment_audit_notification_items i where notification_id=?",String.class,n)).isEqualTo(item);
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void claimedRecoveryAndRestart(boolean off){long r=pending();delivery.claim(UUID.randomUUID()).orElseThrow();expire(r);org.mockito.Mockito.when(properties.enabled()).thenReturn(!off);service.recoverExpired();assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.PENDING);assertThat(requests.findById(r).orElseThrow().getClaimToken()).isNull();assertThat(attempts.count()).isZero();if(off){service.deliverOne();assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.PENDING);}org.mockito.Mockito.verifyNoInteractions(client);}
    @ParameterizedTest @ValueSource(booleans={false,true}) void sendingRecoveryRejectsLateResults(boolean off){long r=pending();var claim=delivery.claim(UUID.randomUUID()).orElseThrow();delivery.prepare(claim).orElseThrow();expire(r);org.mockito.Mockito.when(properties.enabled()).thenReturn(!off);service.recoverExpired();assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.UNKNOWN);assertThat(attempts.findByManualResendId(r).orElseThrow().getResultReasonCode()).isEqualTo(PaymentAuditNotificationFailureCode.CLAIM_LOST);assertThat(delivery.complete(claim,PaymentAuditNotificationAttemptResult.SUCCESS,null)).isFalse();service.deliverOne();org.mockito.Mockito.verifyNoInteractions(client);assertThat(attempts.count()).isEqualTo(1);}
    @ParameterizedTest @ValueSource(strings={"HANDLING_RESOLVED","ORIGINAL_UNKNOWN","MANUAL_UNKNOWN","RESOLVED","NO_VALID_RECIPIENTS","UNCERTAIN","NOT_EXHAUSTED","PARTIAL"}) void preflight(String condition){long r=pending();long n=requests.findById(r).orElseThrow().getNotificationId();
        switch(condition){
            case "HANDLING_RESOLVED" -> {long h=history(n,"RESOLVED");jdbc.update("insert into payment_audit_notification_handlings(notification_id,handling_status,created_at,updated_at,last_resolved_history_id) values(?,'RESOLVED',clock_timestamp(),clock_timestamp(),?)",n,h);}
            case "ORIGINAL_UNKNOWN" -> fixtures.attempt(n,1,"UNKNOWN");
            case "MANUAL_UNKNOWN" -> tx(()->{long old=request(n,2,UUID.randomUUID(),"UNKNOWN",UUID.randomUUID());attempt(old,n,"UNKNOWN",UUID.randomUUID());});
            case "RESOLVED" -> jdbc.update("update payment_audit_notification_states set active=false,resolved_at=clock_timestamp() where warning_type='LATEST_FAILURE'");
            case "NO_VALID_RECIPIENTS" -> org.mockito.Mockito.when(properties.recipients()).thenReturn(java.util.List.of("new@example.invalid"));
            case "UNCERTAIN" -> jdbc.update("update payment_audit_notifications set delivery_uncertain=true where id=?",n);
            case "NOT_EXHAUSTED" -> jdbc.update("update payment_audit_notifications set status='SENT',close_reason=null,sent_at=closed_at where id=?",n);
            case "PARTIAL" -> fixtures.item(n,"NO_HISTORY",false);
        }
        service.deliverOne();var actual=requests.findById(r).orElseThrow();
        if(condition.equals("PARTIAL")){assertThat(actual.getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.SENT);assertThat(attempts.findByManualResendId(r).orElseThrow().getBody()).contains("LATEST_FAILURE").doesNotContain("NO_HISTORY");}
        else {assertThat(actual.getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.CANCELLED);assertThat(actual.getResultReasonCode()).isEqualTo(switch(condition){case "HANDLING_RESOLVED"->"HANDLING_RESOLVED";case "RESOLVED"->"RESOLVED";case "NO_VALID_RECIPIENTS"->"NO_VALID_RECIPIENTS";default->"RESEND_PROHIBITED";});assertThat(attempts.findByManualResendId(r)).isEmpty();org.mockito.Mockito.verifyNoInteractions(client);}
    }
    @Test void offBeforeClaimAndPrepare(){long r=pending();org.mockito.Mockito.when(properties.enabled()).thenReturn(false);service.deliverOne();assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.PENDING);assertThat(delivery.claim(UUID.randomUUID())).isEmpty();org.mockito.Mockito.when(properties.enabled()).thenReturn(true);var claim=delivery.claim(UUID.randomUUID()).orElseThrow();org.mockito.Mockito.when(properties.enabled()).thenReturn(false);assertThat(delivery.prepare(claim)).isEmpty();expire(r);service.recoverExpired();assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.PENDING);org.mockito.Mockito.verifyNoInteractions(client);}
    @Test void wrongTokenAndTerminalFencing(){long r=pending();var claim=delivery.claim(UUID.randomUUID()).orElseThrow();delivery.prepare(claim).orElseThrow();var stale=new com.example.ecsite.service.payment.PaymentAuditNotificationManualResendDeliveryTransaction.Claim(r,claim.notificationId(),UUID.randomUUID());assertThat(delivery.complete(stale,PaymentAuditNotificationAttemptResult.SUCCESS,null)).isFalse();assertThat(delivery.complete(claim,PaymentAuditNotificationAttemptResult.SUCCESS,null)).isTrue();assertThat(delivery.complete(claim,PaymentAuditNotificationAttemptResult.FAILURE,PaymentAuditNotificationFailureCode.SMTP_SEND_FAILED)).isFalse();assertThat(delivery.prepare(claim)).isEmpty();assertThat(attempts.count()).isEqualTo(1);}
    @Test void smtpHasNoTransactionAndSendingIsCommitted(){long r=pending();org.mockito.Mockito.doAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.SENDING);assertThat(attempts.findByManualResendId(r).orElseThrow().getResult()).isEqualTo(PaymentAuditNotificationAttemptResult.IN_PROGRESS);new TransactionTemplate(manager).executeWithoutResult(s->{jdbc.execute("set local lock_timeout='500ms'");requests.lockNotification(requests.findById(r).orElseThrow().getNotificationId());requests.findForUpdate(r);});return null;}).when(client).send(org.mockito.ArgumentMatchers.any());org.mockito.Mockito.when(properties.smtp().sendDeadline()).thenReturn(java.time.Duration.ofSeconds(5));service.deliverOne();assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.SENT);}
    @Test void timeoutRetainsExecutorAndConcurrentTickDoesNotClaim() throws Exception {long r=pending();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);org.mockito.Mockito.doAnswer(i->{entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}return null;}).when(client).send(org.mockito.ArgumentMatchers.any());var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{var task=pool.submit(()->service.deliverOne());assertThat(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();task.get(3,java.util.concurrent.TimeUnit.SECONDS);assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.UNKNOWN);long second=pending();service.deliverOne();assertThat(requests.findById(second).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.PENDING);assertThat(smtp.reserve()).isEmpty();}finally{release.countDown();pool.shutdownNow();}org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any());}
    @Test void resultSaveFailureIsRecoveredWithoutReplay(){long r=pending();jdbc.execute("create function reject_manual_result() returns trigger language plpgsql as $$ begin if new.result <> 'IN_PROGRESS' then raise exception 'synthetic'; end if; return new; end $$");jdbc.execute("create trigger reject_manual_result before update on payment_audit_notification_manual_resend_attempts for each row execute function reject_manual_result()");try{assertThatThrownBy(()->service.deliverOne()).isInstanceOf(RuntimeException.class);assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.SENDING);assertThat(attempts.findByManualResendId(r).orElseThrow().getResult()).isEqualTo(PaymentAuditNotificationAttemptResult.IN_PROGRESS);}finally{jdbc.execute("drop trigger reject_manual_result on payment_audit_notification_manual_resend_attempts");jdbc.execute("drop function reject_manual_result()");}expire(r);service.recoverExpired();service.deliverOne();assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.UNKNOWN);org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any());}
    @ParameterizedTest @ValueSource(booleans={false,true}) void newCoordinatorRecoversPersistedPreviousOwner(boolean sending){
        long r=pending();var old=delivery.claim(UUID.randomUUID()).orElseThrow();if(sending)delivery.prepare(old).orElseThrow();expire(r);
        org.mockito.Mockito.when(properties.enabled()).thenReturn(false);
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var restarted=new com.example.ecsite.service.payment.PaymentAuditNotificationManualResendDeliveryService(delivery,factory.getBeanProvider(com.example.ecsite.service.payment.PaymentAuditNotificationSmtpExecutor.class),properties);
        restarted.recoverExpired();
        assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(sending?PaymentAuditNotificationManualResendStatus.UNKNOWN:PaymentAuditNotificationManualResendStatus.PENDING);
        assertThat(delivery.complete(old,PaymentAuditNotificationAttemptResult.SUCCESS,null)).isFalse();restarted.deliverOne();org.mockito.Mockito.verifyNoInteractions(client);
        if(!sending){org.mockito.Mockito.when(properties.enabled()).thenReturn(true);factory.addBean("sharedSmtp",smtp);restarted.deliverOne();assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.SENT);assertThat(attempts.findByManualResendId(r).orElseThrow().getClaimToken()).isNotEqualTo(old.token());}
        assertThat(attempts.count()).isEqualTo(1);
    }
    @Test void acceptedRequestDeliversThroughCommittedStates(){long n=notification();fixtures.item(n,"LATEST_FAILURE",false);jdbc.update("update payment_audit_notification_states set active=true,episode_no=?,first_observed_at=clock_timestamp(),last_observed_at=clock_timestamp() where warning_type='LATEST_FAILURE'",n);assertThat(acceptance.prepare(n,7,false).snapshotToken()).isNull();String token=acceptance.prepare(n,7,true).snapshotToken();long r=acceptance.request(n,7,"fixture-admin",token,"手動再送確認",false);assertThat(acceptance.request(n,7,"fixture-admin",token,"手動再送確認",false)).isEqualTo(r);assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.PENDING);service.deliverOne();assertThat(requests.findById(r).orElseThrow().getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.SENT);org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any());}
}
