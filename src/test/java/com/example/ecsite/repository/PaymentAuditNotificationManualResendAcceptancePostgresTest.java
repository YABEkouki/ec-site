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
import org.springframework.test.annotation.DirtiesContext;
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
@org.springframework.context.annotation.Import(PaymentAuditNotificationManualResendAcceptancePostgresTest.Config.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentAuditNotificationManualResendAcceptancePostgresTest {
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
        @org.springframework.context.annotation.Bean com.example.ecsite.service.PaymentAuditNotificationManualResendToken token(){return new com.example.ecsite.service.PaymentAuditNotificationManualResendToken(java.util.Base64.getEncoder().encodeToString(new byte[32]), java.time.Clock.systemUTC());}
        @org.springframework.context.annotation.Bean com.example.ecsite.service.AdminPaymentAuditNotificationManualResendService service(PaymentAuditNotificationRepository n,PaymentAuditNotificationManualResendRepository r,PaymentAuditNotificationManualResendAttemptRepository a,PaymentAuditNotificationHandlingRepository h,com.example.ecsite.service.PaymentAuditNotificationManualResendToken t,JdbcTemplate j){return new com.example.ecsite.service.AdminPaymentAuditNotificationManualResendService(n,r,a,h,t,j,true);}
    }
    @Autowired com.example.ecsite.service.AdminPaymentAuditNotificationManualResendService service;
    @Autowired com.example.ecsite.service.PaymentAuditNotificationManualResendToken tokenService;
    @Autowired PaymentAuditNotificationRepository notifications;
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

    String token(long n){return service.prepare(n,7).snapshotToken();}
    long accept(long n,String token,String reason,boolean risk){return service.request(n,7,"fixture-admin",token,reason,risk);}
    void denied(long n,com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason reason){
        assertThat(service.prepare(n,7).rejection()).isEqualTo(reason);
        String signed=tokenService.issue(n,7,UUID.randomUUID(),"47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU",false,0);
        assertThatThrownBy(()->accept(n,signed,"理由",false)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.class).satisfies(e->assertThat(((com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException)e).getReason()).isEqualTo(reason));
        assertThat(requests.findByNotificationIdOrderByRequestNoAsc(n)).isEmpty();
    }
    @Test void initialRequestAndIdempotentNonce(){
        long n=notification(); String signed=token(n); var original=jdbc.queryForObject("select row_to_json(n)::text from payment_audit_notifications n where id=?",String.class,n);
        long id=accept(n,signed,"  理由😀  ",false); assertThat(accept(n,signed,"  理由😀  ",false)).isEqualTo(id);
        var r=requests.findById(id).orElseThrow(); assertThat(r.getStatus()).isEqualTo(PaymentAuditNotificationManualResendStatus.PENDING); assertThat(r.getRequestNo()).isEqualTo(1); assertThat(r.getVersion()).isZero(); assertThat(r.getActorUserId()).isEqualTo(7); assertThat(r.getReason()).isEqualTo("  理由😀  "); assertThat(attempts.count()).isZero();
        assertThat(jdbc.queryForObject("select row_to_json(n)::text from payment_audit_notifications n where id=?",String.class,n)).isEqualTo(original);
        assertThatThrownBy(()->accept(n,signed,"違う理由",false)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.class);
        assertThatThrownBy(()->accept(n,signed,"  理由😀  ",true)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.class);
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","CLAIMED","SENDING","RETRY_WAIT","SENT","CANCELLED"}) void nonExhausted(String status){long n=fixtures.notification(status,PaymentAuditNotificationHistoryFixtures.NOW); denied(n,com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason.NOT_EXHAUSTED);}
    @Test void uncertainAndOriginalUnknown(){long n=notification(); jdbc.update("update payment_audit_notifications set delivery_uncertain=true where id=?",n); denied(n,com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason.DELIVERY_UNCERTAIN); jdbc.update("update payment_audit_notifications set delivery_uncertain=false where id=?",n);fixtures.attempt(n,1,"UNKNOWN"); denied(n,com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason.UNKNOWN_PRESENT);}
    @Test void resolvedAndStaleHandling(){long n=notification();String signed=token(n);long h=history(n,"RESOLVED"); jdbc.update("insert into payment_audit_notification_handlings(notification_id,handling_status,version,created_at,updated_at,last_resolved_history_id) values(?,'RESOLVED',0,clock_timestamp(),clock_timestamp(),?)",n,h); assertThat(service.prepare(n,7).rejection()).isEqualTo(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason.HANDLING_RESOLVED);assertThatThrownBy(()->accept(n,signed,"理由",false)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.class);assertThat(requests.count()).isZero();}
    @Test void disabled(){var off=new com.example.ecsite.service.AdminPaymentAuditNotificationManualResendService(notifications,requests,attempts,handlings,tokenService,jdbc,false);long n=notification();String signed=token(n);assertThat(off.prepare(n,7).rejection()).isEqualTo(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason.NOTIFICATION_DISABLED);assertThatThrownBy(()->off.request(n,7,"fixture-admin",signed,"理由",false)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.class);assertThat(requests.count()).isZero();}
    @Test void unicodeReasonBoundaries(){long n=notification();String signed=token(n);for(int cp:new int[]{9,10,11,12,13,32,0x85,0xa0,0x1680,0x2000,0x2001,0x2002,0x2003,0x2004,0x2005,0x2006,0x2007,0x2008,0x2009,0x200a,0x200b,0x2028,0x2029,0x202f,0x205f,0x3000})assertThatThrownBy(()->accept(n,signed,new String(Character.toChars(cp)),false)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->accept(n,signed,"😀".repeat(1001),false)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->accept(n,signed,null,false)).isInstanceOf(IllegalArgumentException.class);accept(n,signed,"😀".repeat(1000),false);}
    long previous(long n,String status){long[] id=new long[1];UUID claim=UUID.randomUUID();tx(()->{id[0]=request(n,1,UUID.randomUUID(),status,claim);if(!status.equals("CANCELLED"))attempt(id[0],n,switch(status){case "FAILED"->"FAILURE";case "SENT"->"SUCCESS";default->"UNKNOWN";},claim);});tx(()->{jdbc.update("update payment_audit_notification_manual_resends set requested_at=clock_timestamp()-interval '30 minutes', started_at=case when status='CANCELLED' then null else clock_timestamp()-interval '29 minutes' end, finished_at=clock_timestamp()-interval '28 minutes' where id=?",id[0]);jdbc.update("update payment_audit_notification_manual_resend_attempts set started_at=clock_timestamp()-interval '29 minutes', finished_at=clock_timestamp()-interval '28 minutes' where manual_resend_id=?",id[0]);});return id[0];}
    @ParameterizedTest @ValueSource(strings={"FAILED","CANCELLED"}) void secondRequestRiskAndMaximum(String status){long n=notification();previous(n,status);String signed=token(n);assertThat(signed).isNotNull();if(status.equals("FAILED")){assertThat(service.prepare(n,7).riskAcknowledgementRequired()).isTrue();assertThatThrownBy(()->accept(n,signed,"理由",false)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.class);}long id=accept(n,signed,"理由",status.equals("FAILED"));assertThat(requests.findById(id).orElseThrow().getRequestNo()).isEqualTo(2);assertThat(service.prepare(n,7).rejection()).isEqualTo(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason.LIMIT_REACHED);assertThat(requests.count()).isEqualTo(2);}
    @Test void thirtyMinuteBoundary(){long n=notification();long r=previous(n,"CANCELLED");jdbc.update("update payment_audit_notification_manual_resends set requested_at=clock_timestamp()-interval '29 minutes 59 seconds' where id=?",r);assertThat(service.prepare(n,7).rejection()).isEqualTo(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason.TOO_SOON);jdbc.update("update payment_audit_notification_manual_resends set requested_at=clock_timestamp()-interval '30 minutes' where id=?",r);accept(n,token(n),"理由",false);}
    @ParameterizedTest @ValueSource(strings={"SENT","UNKNOWN"}) void previousTerminalProhibited(String status){long n=notification();previous(n,status);assertThat(service.prepare(n,7).eligible()).isFalse();assertThat(requests.count()).isEqualTo(1);}
    @Test void activeAndOldScreen(){long n=notification();String first=token(n),old=token(n);accept(n,first,"理由",false);assertThat(service.prepare(n,7).rejection()).isEqualTo(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.Reason.ACTIVE_REQUEST);assertThatThrownBy(()->accept(n,old,"理由",false)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.class);assertThat(requests.count()).isEqualTo(1);}
    @Test void tamperedExpiredAndDifferentActor(){long n=notification();String signed=token(n);assertThatThrownBy(()->accept(n,signed+"a","理由",false)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.request(n,8,"other",signed,"理由",false)).isInstanceOf(IllegalArgumentException.class);String expired=new com.example.ecsite.service.PaymentAuditNotificationManualResendToken(java.util.Base64.getEncoder().encodeToString(new byte[32]),java.time.Clock.fixed(java.time.Instant.now().minusSeconds(901),java.time.ZoneOffset.UTC)).issue(n,7,UUID.randomUUID(),"old",false,0);assertThatThrownBy(()->accept(n,expired,"理由",false)).isInstanceOf(IllegalArgumentException.class);assertThat(requests.count()).isZero();}
    @Test void changedUnknownAndCrossNotificationNonce(){
        long n=notification(),other=notification();String signed=token(n);fixtures.attempt(n,1,"UNKNOWN");assertThatThrownBy(()->accept(n,signed,"理由",false)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.class);assertThat(requests.count()).isZero();
        jdbc.update("delete from payment_audit_notification_attempts where notification_id=?",n);long id=accept(n,signed,"理由",false);var first=tokenService.verify(signed,n,7);String reused=tokenService.issue(other,7,first.nonce(),first.generation(),false,0);assertThatThrownBy(()->accept(other,reused,"理由",false)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.class);assertThat(requests.count()).isEqualTo(1);assertThat(requests.findById(id)).isPresent();
    }
    @Test void staleTerminalGeneration(){long n=notification();previous(n,"CANCELLED");String signed=token(n);jdbc.update("update payment_audit_notification_manual_resends set version=version+1 where notification_id=?",n);assertThatThrownBy(()->accept(n,signed,"理由",false)).isInstanceOf(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException.class);assertThat(requests.count()).isEqualTo(1);}
    @ParameterizedTest @ValueSource(booleans={false,true}) void concurrentAdministratorsAreSerialized(boolean second) throws Exception {
        long n=notification();if(second)previous(n,"CANCELLED");String a=token(n),b=service.prepare(n,8).snapshotToken();var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var one=pool.submit(()->{start.await();try{service.request(n,7,"first",a,"理由",false);return true;}catch(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException e){return false;}});var two=pool.submit(()->{start.await();try{service.request(n,8,"second",b,"理由",false);return true;}catch(com.example.ecsite.service.PaymentAuditNotificationManualResendRejectedException e){return false;}});start.countDown();assertThat(java.util.List.of(one.get(15,java.util.concurrent.TimeUnit.SECONDS),two.get(15,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);assertThat(requests.count()).isEqualTo(second?2:1);assertThat(attempts.count()).isZero();}finally{pool.shutdownNow();}
    }
}
