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
@org.springframework.context.annotation.Import({com.example.ecsite.service.AdminPaymentAuditNotificationHandlingService.class,com.example.ecsite.service.AdminPaymentAuditNotificationService.class,com.example.ecsite.service.payment.PaymentAuditNotificationManualResendDeliveryTransaction.class,PaymentAuditNotificationManualUnknownHandlingPostgresTest.Config.class})
@Testcontainers
class PaymentAuditNotificationManualUnknownHandlingPostgresTest {
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
    @org.springframework.boot.test.context.TestConfiguration @org.springframework.boot.context.properties.EnableConfigurationProperties(com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties.class) static class Config {
        @org.springframework.context.annotation.Bean org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator(){return new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();}
        @org.springframework.context.annotation.Bean com.example.ecsite.service.PaymentAuditNotificationHandlingToken token(){return new com.example.ecsite.service.PaymentAuditNotificationHandlingToken(java.util.Base64.getEncoder().encodeToString(new byte[32]),java.time.Clock.systemUTC());}
    }
    @Autowired com.example.ecsite.service.AdminPaymentAuditNotificationHandlingService handling;
    @Autowired com.example.ecsite.service.AdminPaymentAuditNotificationService reader;
    @Autowired com.example.ecsite.service.PaymentAuditNotificationHandlingToken tokens;
    @Autowired com.example.ecsite.service.payment.PaymentAuditNotificationManualResendDeliveryTransaction delivery;
    @Autowired PaymentAuditNotificationHandlingRepository handlings;
    PaymentAuditNotificationHistoryFixtures fixtures;
    void tx(Runnable operation) { new TransactionTemplate(manager).executeWithoutResult(s -> operation.run()); }
    @BeforeEach void setup() throws Exception {
        try (var c = source.getConnection()) { assertThat(c.getMetaData().getURL()).isEqualTo(DB.getJdbcUrl()); }
        assertThat(DB.getMappedPort(5432)).isNotEqualTo(5432);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("feature118_v52_isolated");
        fixtures = new PaymentAuditNotificationHistoryFixtures(jdbc);
        tx(() -> {
            jdbc.update("delete from payment_audit_notification_handling_history_manual_unknowns");
            jdbc.update("delete from payment_audit_notification_manual_resend_attempts");
            jdbc.update("delete from payment_audit_notification_manual_resends");
            fixtures.clear();
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

    String token(long n){return reader.findById(n,7L).handling().snapshotToken();}
    void resolve(long n,String token){handling.changeStatus(n,7,"fixture-admin",token,PaymentAuditNotificationHandlingStatus.RESOLVED,"確認");}
    long manual(long n,String result,int number){long[] a=new long[1];UUID token=UUID.randomUUID();String status=result.equals("IN_PROGRESS")?"SENDING":"UNKNOWN";tx(()->{long r=request(n,number,UUID.randomUUID(),status,token);if(status.equals("SENDING"))jdbc.update("update payment_audit_notification_manual_resends set lease_until=clock_timestamp()+interval '3 minutes' where id=?",r);a[0]=attempt(r,n,result,token);});return a[0];}
    void finalizeUnknown(long n,long a){var row=attempts.findById(a).orElseThrow();assertThat(delivery.complete(new com.example.ecsite.service.payment.PaymentAuditNotificationManualResendDeliveryTransaction.Claim(row.getManualResendId(),n,row.getClaimToken()),PaymentAuditNotificationAttemptResult.UNKNOWN,PaymentAuditNotificationFailureCode.CLAIM_LOST)).isTrue();}
    @ParameterizedTest @CsvSource({"false,false","true,false","false,true","true,true"}) void independentSnapshots(boolean auto,boolean manual){long n=notification();if(auto)fixtures.attempt(n,1,"UNKNOWN");long id=manual?manual(n,"UNKNOWN",1):0;resolve(n,token(n));var d=reader.findById(n).handling();assertThat(d.currentUnknowns()).isEqualTo(auto?java.util.List.of(1):java.util.List.of());assertThat(d.currentManualUnknowns()).isEqualTo(manual?java.util.List.of(id):java.util.List.of());assertThat(d.confirmedUnknowns()).isEqualTo(d.currentUnknowns());assertThat(d.confirmedManualUnknowns()).isEqualTo(d.currentManualUnknowns());assertThat(d.requiresRecheck()).isFalse();assertThat(handling.needsReconfirmation(n)).isFalse();}
    @Test void oldPageRejectedWhenManualUnknownFinalizesFirst(){long n=notification();long a=manual(n,"IN_PROGRESS",1);String old=token(n);finalizeUnknown(n,a);assertThatThrownBy(()->resolve(n,old)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationHandlingConflictException.class);assertThat(handlings.findById(n)).isEmpty();assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_handling_histories",Long.class)).isZero();assertThat(unknowns.count()).isZero();}
    @Test void resolutionFirstThenUnknownKeepsResolvedAndNeedsRecheck(){long n=notification();long a=manual(n,"IN_PROGRESS",1);resolve(n,token(n));long history=handlings.findById(n).orElseThrow().getLastResolvedHistoryId();finalizeUnknown(n,a);assertThat(handlings.findById(n).orElseThrow().getHandlingStatus()).isEqualTo(PaymentAuditNotificationHandlingStatus.RESOLVED);assertThat(handling.needsReconfirmation(n)).isTrue();assertThat(reader.findById(n).handling().requiresRecheck()).isTrue();handling.changeStatus(n,7,"fixture-admin",token(n),PaymentAuditNotificationHandlingStatus.IN_PROGRESS,"再確認");assertThat(handlings.findById(n).orElseThrow().getLastResolvedHistoryId()).isEqualTo(history);resolve(n,token(n));assertThat(handling.needsReconfirmation(n)).isFalse();assertThat(reader.findById(n).handling().confirmedManualUnknowns()).containsExactly(a);}
    @Test void listPredicateAndCountUseSameManualMembership(){for(int i=0;i<25;i++){long n=notification();fixtures.item(n,"LATEST_FAILURE",false);long a=manual(n,"IN_PROGRESS",1);resolve(n,token(n));finalizeUnknown(n,a);}long confirmed=notification();manual(confirmed,"UNKNOWN",1);resolve(confirmed,token(confirmed));var f=new com.example.ecsite.form.AdminPaymentAuditNotificationSearchForm();f.setRequiresRecheck(true);f.setHandlingStatus(PaymentAuditNotificationHandlingStatus.RESOLVED);f.setWarningType(PaymentAuditNotificationWarningType.LATEST_FAILURE);var p=reader.search(f,0,20);assertThat(p.getTotalElements()).isEqualTo(25);assertThat(p.getContent()).hasSize(20);assertThat(p.getContent()).allMatch(x->x.requiresRecheck());assertThat(reader.search(f,1,20).getContent()).hasSize(5);}
    @ParameterizedTest @ValueSource(booleans={false,true}) void parentLockOrdersConcurrentFinalization(boolean manualFirst) throws Exception {
        long n=notification();long a=manual(n,"IN_PROGRESS",1);String old=token(n);var locked=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var first=pool.submit(()->{new TransactionTemplate(manager).executeWithoutResult(s->{handlings.lockNotification(n);locked.countDown();try{if(!finish.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("synchronization timeout");}catch(InterruptedException e){throw new RuntimeException(e);}if(manualFirst){var row=attempts.findById(a).orElseThrow();requests.findForUpdate(row.getManualResendId());attempts.findForDelivery(row.getManualResendId());jdbc.update("update payment_audit_notification_manual_resend_attempts set result='UNKNOWN',result_reason_code='CLAIM_LOST',finished_at=clock_timestamp() where id=?",a);jdbc.update("update payment_audit_notification_manual_resends set status='UNKNOWN',result_reason_code='CLAIM_LOST',finished_at=clock_timestamp(),claim_token=null,claimed_by=null,lease_until=null where id=?",row.getManualResendId());}else resolve(n,old);});});assertThat(locked.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var second=pool.submit(()->{if(manualFirst){try{resolve(n,old);return false;}catch(com.example.ecsite.service.PaymentAuditNotificationHandlingConflictException expected){return true;}}else{finalizeUnknown(n,a);return true;}});Thread.sleep(100);assertThat(second.isDone()).isFalse();finish.countDown();first.get(5,java.util.concurrent.TimeUnit.SECONDS);assertThat(second.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();if(manualFirst){assertThat(handlings.findById(n)).isEmpty();assertThat(unknowns.count()).isZero();}else{assertThat(handlings.findById(n).orElseThrow().getHandlingStatus()).isEqualTo(PaymentAuditNotificationHandlingStatus.RESOLVED);assertThat(handling.needsReconfirmation(n)).isTrue();}}finally{finish.countDown();pool.shutdownNow();}
    }
    @Test void snapshotFailureRollsBackBothCollectionsAndHandling(){long n=notification();fixtures.attempt(n,1,"UNKNOWN");manual(n,"UNKNOWN",1);jdbc.execute("create function reject_manual_snapshot() returns trigger language plpgsql as $$ begin raise exception 'synthetic'; end $$");jdbc.execute("create trigger reject_manual_snapshot before insert on payment_audit_notification_handling_history_manual_unknowns for each row execute function reject_manual_snapshot()");try{assertThatThrownBy(()->resolve(n,token(n))).isInstanceOf(RuntimeException.class);assertThat(handlings.findById(n)).isEmpty();assertThat(unknowns.count()).isZero();assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_handling_history_unknowns",Long.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from payment_audit_notification_handling_histories",Long.class)).isZero();}finally{jdbc.execute("drop trigger reject_manual_snapshot on payment_audit_notification_handling_history_manual_unknowns");jdbc.execute("drop function reject_manual_snapshot()");}}
}
