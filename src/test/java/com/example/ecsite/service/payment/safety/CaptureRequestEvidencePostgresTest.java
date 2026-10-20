package com.example.ecsite.service.payment.safety;

import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.payment.PaymentSafetySnapshot.CaptureVerification.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import com.example.ecsite.support.isolation.IsolatedDatabaseGuard;
import com.example.ecsite.payment.capture.*;
import com.example.ecsite.service.payment.*;

/** JDBC -> detached verification -> assembler -> unchanged classifier, container only. */
class CaptureRequestEvidencePostgresTest {
    static PGSimpleDataSource source;static JdbcTemplate jdbc;
    long order,payment,capture;
    @BeforeAll static void isolatedDatabase() {
        String url=System.getenv("EC_TEST_DB_URL"),user=System.getenv("EC_TEST_DB_USER"),password=System.getenv("EC_TEST_DB_PASSWORD");
        var env=new MockEnvironment();
        for(String prefix:List.of("spring.datasource","spring.flyway")) {
            env.setProperty(prefix+".url",Objects.toString(url,""));env.setProperty(prefix+(prefix.endsWith("flyway")?".user":".username"),Objects.toString(user,""));env.setProperty(prefix+".password",Objects.toString(password,""));
        }
        IsolatedDatabaseGuard.verify(env); // Validate Docker provenance before any test JDBC/Flyway.
        source=new PGSimpleDataSource();source.setURL(url);source.setUser(user);source.setPassword(password);source.setCurrentSchema("evidence_"+UUID.randomUUID().toString().replace("-",""));
        Flyway.configure().dataSource(source).defaultSchema(source.getCurrentSchema()).target("55").load().migrate();jdbc=new JdbcTemplate(source);
    }
    void fixture(boolean request,boolean validFingerprint) {
        long user=jdbc.queryForObject("insert into users(username,password,created_at,updated_at) values (?,'unused',localtimestamp,localtimestamp) returning id",Long.class,UUID.randomUUID().toString());
        order=jdbc.queryForObject("insert into orders(user_id,total_amount,content_revision,change_deadline_at) values (?,5500,1,localtimestamp) returning id",Long.class,user);
        String flow="pfw_"+UUID.randomUUID().toString().replace("-","");
        payment=jdbc.queryForObject("insert into payments(order_id,provider,payment_method,status,amount,provider_payment_id) values (?,'PAYJP','CARD','AUTHORIZED',7000,?) returning id",Long.class,order,flow);
        jdbc.update("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,provider_transaction_id,created_at,completed_at,initiator_type,initiator_id,initiator_username) values (?,'AUTHORIZE','SUCCEEDED',7000,0,?,?,timestamp '2026-01-01 08:00:00',timestamp '2026-01-01 08:01:00','USER',?,'user')",payment,UUID.randomUUID().toString(),flow,user);
        String key=UUID.randomUUID().toString();
        capture=jdbc.queryForObject("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,created_at,initiator_type,initiator_id,initiator_username) values (?,'CAPTURE','PENDING',5500,1,?,timestamp '2026-01-01 09:00:00.123456','ADMIN',42,'admin') returning id",Long.class,payment,key);
        jdbc.update("update orders set status='SHIPPED',shipped_at=timestamp '2026-01-01 09:00:00.123456',shipping_preparation_started_at=timestamp '2026-01-01 08:59:00',shipping_preparation_started_by_account_id=42,shipping_preparation_started_by_username='admin',shipped_by_account_id=42,shipped_by_username='admin',shipment_capture_payment_id=?,active_payment_id=? where id=?",payment,payment,order);
        jdbc.update("insert into order_status_histories(order_id,to_status,changed_by_type,changed_by_account_id,changed_by_username) values (?,'ORDERED','USER',?,'user')",order,user);
        jdbc.update("insert into order_status_histories(order_id,from_status,to_status,changed_by_type,changed_by_account_id,changed_by_username) values (?,'ORDERED','SHIPPED','ADMIN',42,'admin')",order);
        if(request) {
            var http=CaptureHttpRequest.create(CaptureRequestEvidenceVerifierTest.IDENTITY,flow,5500,key);
            jdbc.update("insert into capture_requests(payment_id,capture_transaction_id,transaction_type,provider,payment_method,provider_flow_id,connection_id,provider_account_ref,mode,api_origin,http_method,api_path,content_type,amount_to_capture,order_content_revision,idempotency_key,body_format_version,request_body,fingerprint_version,request_fingerprint,created_at,replay_not_after) values (?,?,'CAPTURE','PAYJP','CARD',?,'primary','merchant','TEST','https://api.pay.jp','POST',?,'application/json',5500,1,?,1,?,1,?,timestamptz '2026-01-01 00:00:00.123456+00',timestamptz '2026-01-01 23:00:00.123456+00')",payment,capture,flow,http.path(),key,http.body(),validFingerprint?http.fingerprint():new byte[32]);
        }
    }
    CaptureEvidenceProfiles approved() {
        return new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("manifest-v1","operator-approval",Map.of(capture,CaptureRequestEvidenceVerifierTest.IDENTITY)),
            new CaptureEvidenceProfiles.TimestampBindingProfile("feature123-v1","historical-binding-approval",Map.of(capture,"Asia/Tokyo")));
    }
    PaymentSafetyReadFacts read() throws SQLException {
        try(var c=source.getConnection()) {
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {return new JdbcPaymentSafetySnapshotReader().readLocked(c,order,PaymentSafetyReadContext.defaults()).orElseThrow();}finally{c.rollback();}
        }
    }
    @Test void realV54BytesVerifyForDecreasedPendingCaptureWithoutMutationOrPostPermission() throws Exception {
        fixture(true,true);
        String before=jdbc.queryForObject("select to_jsonb(r)::text from capture_requests r where capture_transaction_id=?",String.class,capture);
        var a=new PaymentSafetySnapshotAssembler().assemble(read(),approved());
        assertThat(a.evidence().captures().getFirst().verification()).isEqualTo(VERIFIED);
        var classification=new PaymentSafetyClassifier().classify(a.snapshot());
        assertThat(classification.disposition()).isEqualTo(PaymentSafetyClassification.ActivePaymentDisposition.SELECTED);
        assertThat(classification.financialResumption()).isEqualTo(PaymentSafetyClassification.FinancialResumption.NOT_EVALUATED);
        assertThat(a.snapshot().payments().getFirst().amount()).isEqualTo(7000);assertThat(a.snapshot().captureEvidence().getFirst().amount()).isEqualTo(5500);
        assertThat(a.facts().order().activePaymentId()).isEqualTo(payment);assertThat(a.snapshot().timeOrigins()).isEmpty();
        assertThat(jdbc.queryForObject("select to_jsonb(r)::text from capture_requests r where capture_transaction_id=?",String.class,capture)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,capture)).isEqualTo("PENDING");
    }
    @Test void noStoredRequestProducesMissingAndReview() throws Exception {
        fixture(false,true);var a=new PaymentSafetySnapshotAssembler().assemble(read(),approved());
        assertThat(a.evidence().captures().getFirst().verification()).isEqualTo(MISSING);
        assertThat(new PaymentSafetyClassifier().classify(a.snapshot()).disposition()).isEqualTo(PaymentSafetyClassification.ActivePaymentDisposition.REVIEW);
    }
    @Test void databaseAcceptedIncorrectHashIsDetectedWithoutChangingV54Triggers() throws Exception {
        fixture(true,false);var a=new PaymentSafetySnapshotAssembler().assemble(read(),approved());
        assertThat(a.evidence().captures().getFirst().verification()).isEqualTo(MISMATCH);
        assertThat(new PaymentSafetyClassifier().classify(a.snapshot()).disposition()).isEqualTo(PaymentSafetyClassification.ActivePaymentDisposition.REVIEW);
    }
    @Test void approvedConnectionMissingOrDifferentIsNotPromotedToVerified() throws Exception {
        fixture(true,true);var facts=read();var assembler=new PaymentSafetySnapshotAssembler();
        assertThat(assembler.assemble(facts,CaptureEvidenceProfiles.none()).evidence().captures().getFirst().verification()).isEqualTo(UNVERIFIED);
        var wrong=new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("other","approval",Map.of(capture,new CaptureConnectionIdentity("other","merchant","LIVE","https://api.pay.jp"))),approved().timestampBinding());
        assertThat(assembler.assemble(facts,wrong).evidence().captures().getFirst().verification()).isEqualTo(MISMATCH);
    }
    @Test void finalInputFingerprintIsStableAndIncludesEffectiveEvidence() throws Exception {
        fixture(true,true);var assembler=new PaymentSafetySnapshotAssembler();var a=assembler.assemble(read(),approved());
        assertThat(a.inputFingerprint()).isEqualTo(assembler.assemble(read(),approved()).inputFingerprint());
        assertThat(a.inputFingerprint()).isNotEqualTo(assembler.assemble(read(),CaptureEvidenceProfiles.none()).inputFingerprint());
        assertThat(a.inputFingerprint()).isNotEqualTo(PaymentSafetyInputFingerprint.sha256(a.facts()));
    }
    @Test void sqlAcquisitionFailureCannotReachMissingEvidenceResult() throws Exception {
        fixture(true,true);
        try(var c=source.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) {
                s.execute("alter table capture_requests rename column request_body to unavailable_body");
                assertThatThrownBy(()->new JdbcPaymentSafetySnapshotReader().readLocked(c,order,PaymentSafetyReadContext.defaults())).isInstanceOf(SQLException.class);
            } finally {c.rollback();}
        }
        assertThat(new PaymentSafetySnapshotAssembler().assemble(read(),approved()).evidence().captures().getFirst().verification()).isEqualTo(VERIFIED);
    }
}
