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
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentAuditNotificationManualResendPostgresTest {
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

    @Test void migrationAndHibernateValidation() {
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success and version is not null", Integer.class)).isEqualTo(57);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where not success", Integer.class)).isZero();
    }
    @ParameterizedTest @CsvSource({"PENDING,NONE","CLAIMED,NONE","CANCELLED,NONE","SENDING,IN_PROGRESS","SENT,SUCCESS","FAILED,FAILURE","UNKNOWN,UNKNOWN"})
    void legalFinalStatesAndIntermediateFlushes(String status, String result) {
        long n=notification(); UUID token=UUID.randomUUID();
        tx(() -> { long id=request(n,1,UUID.randomUUID(),status,token); if (!result.equals("NONE")) attempt(id,n,result,token); });
        assertThat(requests.findByNotificationIdOrderByRequestNoAsc(n)).hasSize(1);
    }
    @ParameterizedTest @CsvSource({"SENT,FAILURE","FAILED,SUCCESS","UNKNOWN,FAILURE","SENDING,SUCCESS","PENDING,SUCCESS","CANCELLED,SUCCESS"})
    void mismatchedFinalStatesRejectCommitAndRollback(String status, String result) {
        long n=notification(); UUID token=UUID.randomUUID();
        rejects(() -> { long id=request(n,1,UUID.randomUUID(),status,token); attempt(id,n,result,token); });
        assertThat(requests.findByNotificationIdOrderByRequestNoAsc(n)).isEmpty(); assertThat(attempts.count()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"SENDING","SENT","FAILED","UNKNOWN"})
    void startedRequestWithoutAttemptRejectsCommit(String status) { long n=notification(); rejects(() -> request(n,1,UUID.randomUUID(),status,UUID.randomUUID())); }
    @ParameterizedTest @ValueSource(ints={0,3})
    void invalidRequestNumber(int number) { long n=notification(); rejects(() -> request(n,number,UUID.randomUUID(),"PENDING",UUID.randomUUID())); }
    @Test void uniqueNonceNumberAndActiveRequest() {
        long n=notification(), other=notification(); UUID nonce=UUID.randomUUID();
        tx(() -> request(n,1,nonce,"PENDING",UUID.randomUUID()));
        rejects(() -> request(other,1,nonce,"PENDING",UUID.randomUUID()));
        rejects(() -> request(n,1,UUID.randomUUID(),"CANCELLED",UUID.randomUUID()));
        rejects(() -> request(n,2,UUID.randomUUID(),"PENDING",UUID.randomUUID()));
        tx(() -> request(n,2,UUID.randomUUID(),"CANCELLED",UUID.randomUUID()));
    }
    @Test void oneAttemptAndSameNotificationForeignKey() {
        long n=notification(), other=notification(); UUID token=UUID.randomUUID();
        rejects(() -> { long id=request(n,1,UUID.randomUUID(),"SENT",token); attempt(id,other,"SUCCESS",token); });
        rejects(() -> { long id=request(n,1,UUID.randomUUID(),"SENT",token); attempt(id,n,"SUCCESS",token); attempt(id,n,"SUCCESS",UUID.randomUUID()); });
    }
    @Test void sendingTokenAndReasonAgreement() {
        long n=notification();
        rejects(() -> {long id=request(n,1,UUID.randomUUID(),"SENDING",UUID.randomUUID()); attempt(id,n,"IN_PROGRESS",UUID.randomUUID());});
        rejects(() -> {long id=request(n,1,UUID.randomUUID(),"UNKNOWN",UUID.randomUUID()); long a=attempt(id,n,"UNKNOWN",UUID.randomUUID()); jdbc.update("update payment_audit_notification_manual_resend_attempts set result_reason_code='SEND_DEADLINE_EXCEEDED' where id=?",a);});
    }
    @ParameterizedTest @ValueSource(strings={"status='INVALID'","version=-1","reason=U&'\\200B\\00A0'","claim_token=null","started_at=null","finished_at=requested_at - interval '1 second'"})
    void requestChecks(String assignment) {
        long n=notification();
        rejects(() -> {UUID token=UUID.randomUUID(); long id=request(n,1,UUID.randomUUID(),"SENDING",token); attempt(id,n,"IN_PROGRESS",token); jdbc.update("update payment_audit_notification_manual_resends set " + assignment + " where id=?",id);});
    }
    @ParameterizedTest @ValueSource(strings={"result='INVALID'","finished_at=null","result_reason_code=null","result_reason_code='INVALID'","recipients=ARRAY[]::text[]","recipients=ARRAY[NULL]::text[]","recipients=ARRAY['']::text[]","finished_at=started_at - interval '1 second'"})
    void attemptChecks(String assignment) {
        long n=notification();
        rejects(() -> {long r=request(n,1,UUID.randomUUID(),"FAILED",UUID.randomUUID()); long a=attempt(r,n,"FAILURE",UUID.randomUUID()); jdbc.update("update payment_audit_notification_manual_resend_attempts set " + assignment + " where id=?",a);});
    }
    @Test void uniqueAttemptClaimToken() {
        long n=notification(), other=notification(); UUID token=UUID.randomUUID();
        rejects(() -> {long r=request(n,1,UUID.randomUUID(),"SENT",token); attempt(r,n,"SUCCESS",token); long second=request(other,1,UUID.randomUUID(),"SENT",token); attempt(second,other,"SUCCESS",token);});
    }
    @Test void resolvedAndUnknownOnlySameNotificationSnapshot() {
        long n=notification(), other=notification(); long[] ids=new long[4];
        tx(() -> {long r=request(n,1,UUID.randomUUID(),"UNKNOWN",UUID.randomUUID()); ids[0]=attempt(r,n,"UNKNOWN",UUID.randomUUID()); ids[1]=history(n,"RESOLVED"); ids[2]=history(n,"IN_PROGRESS"); ids[3]=history(other,"RESOLVED");});
        tx(() -> snapshot(ids[1],ids[0],n));
        rejects(() -> snapshot(ids[1],ids[0],n));
        rejects(() -> snapshot(ids[2],ids[0],n));
        rejects(() -> snapshot(ids[3],ids[0],other));
        rejects(() -> snapshot(ids[1],ids[0],other));
        rejects(() -> snapshot(ids[1],Long.MAX_VALUE,n));
        assertThat(unknowns.findByHistoryIdOrderByManualAttemptIdAsc(ids[1])).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"SUCCESS","FAILURE","IN_PROGRESS"})
    void nonUnknownAttemptCannotBeConfirmed(String result) {
        long n=notification(); String status=switch(result){case "SUCCESS" -> "SENT"; case "FAILURE" -> "FAILED"; default -> "SENDING";}; UUID token=UUID.randomUUID(); long[] ids=new long[2];
        tx(() -> {long r=request(n,1,UUID.randomUUID(),status,token); ids[0]=attempt(r,n,result,token); ids[1]=history(n,"RESOLVED");});
        rejects(() -> snapshot(ids[1],ids[0],n));
    }
    @Test void entityRepositoryRoundTripAndVersion() {
        long n=notification(); long[] id=new long[1];
        tx(() -> {var r=new PaymentAuditNotificationManualResend(); r.setNotificationId(n); r.setRequestNo(1); r.setRequestNonce(UUID.randomUUID()); r.setActorUserId(7L); r.setActorUsername("fixture-admin"); r.setReason("再送理由"); r.setStatus(PaymentAuditNotificationManualResendStatus.PENDING); r.setRequestedAt(PaymentAuditNotificationHistoryFixtures.NOW); id[0]=requests.saveAndFlush(r).getId();});
        tx(() -> {var r=requests.findById(id[0]).orElseThrow(); assertThat(r.getVersion()).isZero(); r.setReason("変更した検証理由"); requests.saveAndFlush(r);});
        assertThat(requests.findById(id[0]).orElseThrow().getVersion()).isEqualTo(1);
        long[] a=new long[1], h=new long[1];
        tx(() -> {var r=requests.findById(id[0]).orElseThrow(); r.setStatus(PaymentAuditNotificationManualResendStatus.UNKNOWN); r.setStartedAt(PaymentAuditNotificationHistoryFixtures.NOW.plusSeconds(1)); r.setFinishedAt(PaymentAuditNotificationHistoryFixtures.NOW.plusSeconds(2)); r.setResultReasonCode("CLAIM_LOST"); requests.saveAndFlush(r);
            var attempt=new PaymentAuditNotificationManualResendAttempt(); attempt.setManualResendId(id[0]); attempt.setNotificationId(n); attempt.setResult(PaymentAuditNotificationAttemptResult.UNKNOWN); attempt.setClaimToken(UUID.randomUUID()); attempt.setStartedAt(r.getStartedAt()); attempt.setFinishedAt(r.getFinishedAt()); attempt.setResultReasonCode(PaymentAuditNotificationFailureCode.CLAIM_LOST); attempt.setRecipients(new String[]{"fixture@example.invalid"}); attempt.setSubject("検証"); attempt.setBody("検証本文"); a[0]=attempts.saveAndFlush(attempt).getId(); h[0]=history(n,"RESOLVED");
            var s=new PaymentAuditNotificationHandlingHistoryManualUnknown(); s.setHistoryId(h[0]); s.setManualAttemptId(a[0]); s.setNotificationId(n); unknowns.saveAndFlush(s);
        });
        assertThat(attempts.findByManualResendId(id[0]).orElseThrow().getResult()).isEqualTo(PaymentAuditNotificationAttemptResult.UNKNOWN);
        assertThat(attempts.findUnknownIdsByNotificationId(n)).containsExactly(a[0]);
        assertThat(unknowns.findById(new PaymentAuditNotificationHandlingHistoryManualUnknownId(h[0],a[0]))).isPresent();
    }
}
