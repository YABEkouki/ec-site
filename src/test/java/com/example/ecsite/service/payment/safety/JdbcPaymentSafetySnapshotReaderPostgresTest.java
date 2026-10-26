package com.example.ecsite.service.payment.safety;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import com.example.ecsite.support.isolation.IsolatedDatabaseGuard;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadContext.Limits;

class JdbcPaymentSafetySnapshotReaderPostgresTest {
    static PGSimpleDataSource source;
    static JdbcTemplate jdbc;
    JdbcPaymentSafetySnapshotReader reader=new JdbcPaymentSafetySnapshotReader();
    PaymentSafetyReadContext context=PaymentSafetyReadContext.defaults();
    long order;
    @BeforeAll static void isolatedDatabase() {
        String url=System.getenv("EC_TEST_DB_URL"),user=System.getenv("EC_TEST_DB_USER"),password=System.getenv("EC_TEST_DB_PASSWORD");
        var env=new MockEnvironment();
        for(String prefix:List.of("spring.datasource","spring.flyway")) {
            env.setProperty(prefix+".url",Objects.toString(url,""));
            env.setProperty(prefix+(prefix.endsWith("flyway")?".user":".username"),Objects.toString(user,""));
            env.setProperty(prefix+".password",Objects.toString(password,""));
        }
        IsolatedDatabaseGuard.verify(env); // before Flyway, DataSource or any test JDBC connection
        String schema="reader_"+UUID.randomUUID().toString().replace("-","");
        source=new PGSimpleDataSource();source.setURL(url);source.setUser(user);source.setPassword(password);source.setCurrentSchema(schema);
        Flyway.configure().dataSource(source).defaultSchema(schema).target("55").load().migrate();
        jdbc=new JdbcTemplate(source);
    }
    @BeforeEach void fixture() {
        long user=jdbc.queryForObject("insert into users(username,password,created_at,updated_at) values (?,'unused',localtimestamp,localtimestamp) returning id",Long.class,UUID.randomUUID().toString());
        order=jdbc.queryForObject("insert into orders(user_id,total_amount,change_deadline_at) values (?,5500,localtimestamp) returning id",Long.class,user);
    }
    long payment() { return payment(order); }
    long payment(long owner) {
        return jdbc.queryForObject("insert into payments(order_id,provider,payment_method,status,amount) values (?,'PAYJP','CARD','AUTHORIZED',5500) returning id",Long.class,owner);
    }
    long transaction(long payment,String type,String status,String purpose) {
        return jdbc.queryForObject("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,purpose) values (?,?,?,5500,0,?,?) returning id",Long.class,payment,type,status,UUID.randomUUID().toString(),purpose);
    }
    Connection connection() throws SQLException {
        var c=source.getConnection();c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);return c;
    }
    PaymentSafetyReadFacts read() throws SQLException {
        try(var c=connection()) { var facts=reader.readLocked(c,order,context).orElseThrow();c.rollback();return facts; }
    }
    PaymentSafetyReadContext limits(int p,int t,int h,int r,long bytes,int lockMs) {
        return new PaymentSafetyReadContext(new Limits(p,t,h,r,bytes,10,10,lockMs),Map.of(),Map.of());
    }
    @Test void nonexistentOrderOnlyReturnsEmpty() throws Exception {
        try(var c=connection()) { assertThat(reader.readLocked(c,Long.MAX_VALUE,context)).isEmpty();assertThat(c.isClosed()).isFalse();assertThat(c.getAutoCommit()).isFalse();c.rollback(); }
    }
    @Test void allPaymentsTransactionsAndRawNullValuesAreRetained() throws Exception {
        long a=payment(),b=payment();
        for(String status:List.of("PENDING","SUCCEEDED","FAILED","UNKNOWN_STATUS"))transaction(a,"Unknown Type",status,null);
        transaction(b,"FUTURE_TYPE","Raw Status","LEGACY_UNKNOWN");
        jdbc.update("update payments set provider=' raw ',payment_method='Future',status='Unknown',provider_payment_id=null where id=?",b);
        jdbc.update("update orders set shipping_preparation_started_at=timestamp '2026-10-20 13:59:59.123456',shipping_preparation_started_by_account_id=42,shipping_preparation_started_by_username=' Raw Name ',active_payment_id=? where id=?",a,order);
        var f=read();
        assertThat(f.payments()).extracting(p->p.id()).containsExactly(a,b);
        assertThat(f.transactions()).hasSize(5).extracting(t->t.status()).containsExactly("PENDING","SUCCEEDED","FAILED","UNKNOWN_STATUS","Raw Status");
        assertThat(f.transactions().get(0).purpose()).isNull();assertThat(f.transactions().get(0).completedAt()).isNull();
        assertThat(f.transactions().get(0).initiatorId()).isNull();assertThat(f.transactions().get(0).internalNote()).isNull();
        assertThat(f.transactions().get(4).purpose()).isEqualTo("LEGACY_UNKNOWN");
        assertThat(f.payments().get(1).provider()).isEqualTo(" raw ");assertThat(f.payments().get(1).providerPaymentId()).isNull();
        assertThat(f.order().shippingPreparationStartedAt()).isEqualTo(LocalDateTime.parse("2026-10-20T13:59:59.123456"));
        assertThat(f.order().shippingPreparationStartedByUsername()).isEqualTo(" Raw Name ");
        assertThat(f.order().activePaymentId()).isEqualTo(a);assertThat(f.timeOrigins()).isEmpty();
    }
    @Test void futurePurposeIsReadRawWhenImportedWithoutCurrentConstraint() throws Exception {
        long p=payment();
        try(var c=connection();var s=c.createStatement()) {
            // Container-only transactional future/import fixture. V55 still rejects this value
            // in normal operation. Rollback restores the constraint and removes the fixture.
            s.execute("alter table payment_transactions drop constraint chk_payment_transactions_purpose_type_1b");
            try(var q=c.prepareStatement("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,purpose) values (?,'Future Type','Unknown Status',5500,0,?,' Future Purpose ')")) {
                q.setLong(1,p);q.setString(2,UUID.randomUUID().toString());q.executeUpdate();
            }
            var f=reader.readLocked(c,order,context).orElseThrow();
            assertThat(f.transactions().getFirst().purpose()).isEqualTo(" Future Purpose ");c.rollback();
        }
    }
    @Test void mappingFailureIsNeverAnEmptySuccessfulRead() throws Exception {
        try(var c=connection();var s=c.createStatement()) {
            s.execute("alter table orders alter column paid_at type text using paid_at::text");
            s.executeUpdate("update orders set paid_at='unparseable' where id="+order);
            assertThatThrownBy(()->reader.readLocked(c,order,context)).isInstanceOf(SQLException.class);c.rollback();
        }
        assertThat(read().order().paidAt()).isNull();
    }
    @Test void noHistoryIsDifferentFromAcquisitionFailure() throws Exception {
        assertThat(read().statusHistory()).isEmpty();
        try(var c=connection();var s=c.createStatement()) {
            // Transactional fixture failure, never committed; restore is caller rollback.
            s.execute("alter table order_status_histories rename column changed_at to unavailable_changed_at");
            assertThatThrownBy(()->reader.readLocked(c,order,context)).isInstanceOf(SQLException.class);c.rollback();
        }
        assertThat(read().statusHistory()).isEmpty();
    }
    @Test void historyNotesArePreservedInCanonicalInput() throws Exception {
        jdbc.update("insert into order_status_histories(order_id,to_status,changed_by_type,changed_by_username,internal_note) values (?,'ORDERED','USER','actor',' raw note ')",order);
        var initial=read();
        assertThat(initial.statusHistory().getFirst().internalNote()).isEqualTo(" raw note ");
        String before=PaymentSafetyInputFingerprint.sha256(initial);
        jdbc.update("update order_status_histories set internal_note='different note' where order_id=?",order);
        assertThat(PaymentSafetyInputFingerprint.sha256(read())).isNotEqualTo(before);
    }
    @Test void allHistoriesAndRealDatabaseTimestampValuesAreReadWithoutInventedOrigins() throws Exception {
        jdbc.update("insert into order_status_histories(order_id,to_status,changed_by_type,changed_by_username) values (?,'ORDERED','USER','first')",order);
        jdbc.update("insert into order_status_histories(order_id,from_status,to_status,changed_by_type,changed_by_username,changed_at) values (?,'ORDERED','CANCELLED','USER','second',clock_timestamp())",order);
        var f=read();assertThat(f.statusHistory()).hasSize(2);assertThat(f.timeOrigins()).isEmpty();
        assertThat(f.statusHistory().get(0).fromStatus()).isNull();
        assertThat(f.statusHistory().get(0).changedAt()).isEqualTo(jdbc.queryForObject("select changed_at from order_status_histories where id=?",LocalDateTime.class,f.statusHistory().get(0).id()));
    }
    @Test void everyV54RequestIncludesOriginalBytesAndPersistedInstants() throws Exception {
        for(int n=0;n<2;n++) {
            long p=payment();String flow="pfw_"+UUID.randomUUID().toString().replace("-","");
            jdbc.update("update payments set provider_payment_id=? where id=?",flow,p);
            long t=transaction(p,"CAPTURE","PENDING",null);
            String key=jdbc.queryForObject("select idempotency_key from payment_transactions where id=?",String.class,t);
            jdbc.update("insert into capture_requests(payment_id,capture_transaction_id,transaction_type,provider,payment_method,provider_flow_id,connection_id,provider_account_ref,mode,api_origin,http_method,api_path,content_type,amount_to_capture,order_content_revision,idempotency_key,body_format_version,request_body,fingerprint_version,request_fingerprint,created_at,replay_not_after) values (?,?,'CAPTURE','PAYJP','CARD',?,'primary','merchant','TEST','https://api.pay.jp','POST',?,'application/json',5500,0,?,1,convert_to('{\"amount_to_capture\":5500}','UTF8'),1,decode(repeat('ab',32),'hex'),timestamptz '2026-01-01 09:00:00.123456+09',timestamptz '2026-01-02 08:00:00.123456+09')",p,t,flow,"/v2/payment_flows/"+flow+"/capture",key);
        }
        var f=read();assertThat(f.captureRequests()).hasSize(2);
        assertThat(f.captureRequests().get(0).requestBody().value()).isEqualTo("{\"amount_to_capture\":5500}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(f.captureRequests().get(0).requestFingerprint().value()).isEqualTo(HexFormat.of().parseHex("ab".repeat(32)));
        assertThat(f.captureRequests().get(0).createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
        assertThat(f.captureRequests().get(0).replayNotAfter()).isEqualTo(Instant.parse("2026-01-01T23:00:00.123456Z"));
        try(var c=connection();var s=c.createStatement()) {
            s.execute("set local time zone 'America/New_York'");
            assertThat(PaymentSafetyInputFingerprint.sha256(reader.readLocked(c,order,context).orElseThrow())).isEqualTo(PaymentSafetyInputFingerprint.sha256(f));c.rollback();
        }
        try(var c=connection()) { assertThatThrownBy(()->reader.readLocked(c,order,limits(100,100,100,1,100000,1000))).isInstanceOf(SQLDataException.class);c.rollback(); }
    }
    @Test void rowAndByteLimitsFailWithoutReturningTruncatedFacts() throws Exception {
        long p=payment();payment();transaction(p,"AUTHORIZE","PENDING",null);transaction(p,"CANCEL","FAILED",null);
        jdbc.update("insert into order_status_histories(order_id,to_status,changed_by_type,changed_by_username) values (?,'ORDERED','USER','a'),(?,'ORDERED','USER','b')",order,order);
        for(var limit:List.of(limits(1,100,100,100,100000,1000),limits(100,1,100,100,100000,1000),limits(100,100,1,100,100000,1000),limits(100,100,100,100,10,1000))) {
            try(var c=connection()) { assertThatThrownBy(()->reader.readLocked(c,order,limit)).isInstanceOf(SQLDataException.class);c.rollback(); }
        }
        var complete=read();assertThat(complete.payments()).hasSize(2);
        long exactBytes=PaymentSafetyInputFingerprint.encodedSize(complete);
        try(var c=connection()) {
            assertThat(reader.readLocked(c,order,limits(2,2,2,1,exactBytes,1000))).isPresent();c.rollback();
            assertThatThrownBy(()->reader.readLocked(c,order,limits(2,2,2,1,exactBytes-1,1000))).isInstanceOf(SQLDataException.class);c.rollback();
        }
    }
    @Test void callerOwnsCommitRollbackAndConnectionLifetime() throws Exception {
        try(var c=connection();var s=c.prepareStatement("update orders set total_amount=5400 where id=?")) {
            s.setLong(1,order);s.executeUpdate();
            var f=reader.readLocked(c,order,context).orElseThrow();assertThat(f.order().totalAmount()).isEqualTo(5400);
            assertThat(jdbc.queryForObject("select total_amount from orders where id=?",Integer.class,order)).isEqualTo(5500);
            assertThat(c.isClosed()).isFalse();assertThat(c.getAutoCommit()).isFalse();c.rollback();
        }
        assertThat(read().order().totalAmount()).isEqualTo(5500);
    }
    @Test void rejectsAutoCommitReadOnlyAndWrongIsolation() throws Exception {
        try(var c=source.getConnection()) { assertThatThrownBy(()->reader.readLocked(c,order,context)).isInstanceOf(SQLException.class); }
        try(var c=connection()) { c.setReadOnly(true);assertThatThrownBy(()->reader.readLocked(c,order,context)).isInstanceOf(SQLException.class);c.rollback(); }
        try(var c=connection()) { c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);assertThatThrownBy(()->reader.readLocked(c,order,context)).isInstanceOf(SQLException.class);c.rollback(); }
    }
    @Test void actualServerReadOnlyTransactionIsRejected() throws Exception {
        try(var c=connection();var s=c.createStatement()) {
            s.execute("set transaction read only");
            assertThatThrownBy(()->reader.readLocked(c,order,context)).isInstanceOf(SQLException.class);c.rollback();
        }
    }
    @Test void inputFingerprintIsReproducibleAcrossReadsAndSessionTimeZones() throws Exception {
        transaction(payment(),"AUTHORIZE","PENDING",null);
        String expected=PaymentSafetyInputFingerprint.sha256(read());
        try(var c=connection();var s=c.createStatement()) {
            s.execute("set local time zone 'America/New_York'");
            assertThat(PaymentSafetyInputFingerprint.sha256(reader.readLocked(c,order,context).orElseThrow())).isEqualTo(expected);c.rollback();
        }
    }
    @Test void orderLockSerializesReadersAndRestoresLockTimeout() throws Exception {
        try(var a=connection();var b=connection()) {
            reader.readLocked(a,order,context).orElseThrow();
            assertThatThrownBy(()->reader.readLocked(b,order,limits(100,100,100,100,100000,150)))
                .isInstanceOf(SQLException.class).satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo("55P03"));
            b.rollback();a.rollback();
            try(var s=b.createStatement();var r=s.executeQuery("show lock_timeout")) { r.next();assertThat(r.getString(1)).isEqualTo("0"); }
            reader.readLocked(b,order,context).orElseThrow();
            try(var s=b.createStatement();var r=s.executeQuery("show lock_timeout")) { r.next();assertThat(r.getString(1)).isEqualTo("0"); }b.rollback();
        }
    }
    @Test void paymentLocksAreAcquiredInAscendingIdOrder() throws Exception {
        long low=payment(),high=payment();
        try(var holder=connection();var probe=connection()) {
            try(var s=holder.prepareStatement("select id from payments where id=? for update")) { s.setLong(1,high);s.executeQuery().close(); }
            var executor=Executors.newSingleThreadExecutor();
            try {
                long[] pid=new long[1];var started=new CountDownLatch(1);
                var future=executor.submit(()-> { try(var c=connection()) { try(var s=c.createStatement();var r=s.executeQuery("select pg_backend_pid()")) {r.next();pid[0]=r.getLong(1);} started.countDown();var f=reader.readLocked(c,order,context);c.rollback();return f.orElseThrow(); } });
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();awaitLockWait(pid[0]);
                try(var s=probe.prepareStatement("select id from payments where id=? for update nowait")) {
                    s.setLong(1,low);assertThatThrownBy(s::executeQuery).isInstanceOf(SQLException.class).satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo("55P03"));
                }
                probe.rollback();holder.rollback();assertThat(future.get(10,TimeUnit.SECONDS).payments()).extracting(p->p.id()).containsExactly(low,high);
            } finally { holder.rollback();executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        }
    }
    @Test void membershipChangeWhileWaitingForPaymentLockFailsClosed() throws Exception {
        long p=payment();
        long other=jdbc.queryForObject("insert into orders(user_id,total_amount,change_deadline_at) select user_id,total_amount,change_deadline_at from orders where id=? returning id",Long.class,order);
        try(var writer=connection();var s=writer.prepareStatement("update payments set order_id=? where id=?")) {
            s.setLong(1,other);s.setLong(2,p);s.executeUpdate(); // Deliberately violates the service Order-lock contract.
            var executor=Executors.newSingleThreadExecutor();
            try {
                long[] pid=new long[1];var started=new CountDownLatch(1);
                var future=executor.submit(()-> { try(var c=connection()) { try(var q=c.createStatement();var r=q.executeQuery("select pg_backend_pid()")) {r.next();pid[0]=r.getLong(1);}started.countDown();try { return reader.readLocked(c,order,context); } finally { c.rollback(); } } });
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();awaitLockWait(pid[0]);writer.commit();
                assertThatThrownBy(()->future.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(SQLTransactionRollbackException.class);
            } finally {writer.rollback();executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
        }
        assertThat(read().payments()).isEmpty();
    }
    void awaitLockWait(long pid) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<end) {
            Boolean waiting=jdbc.queryForObject("select coalesce(wait_event_type='Lock',false) from pg_stat_activity where pid=?",Boolean.class,pid);
            if(Boolean.TRUE.equals(waiting))return;
            Thread.sleep(20);
        }
        throw new AssertionError("Reader did not reach the expected database lock wait");
    }
}
