package com.example.ecsite.payment.capture;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CaptureRequestPostgresTest {
    @Autowired JdbcTemplate jdbc;

    @Test void migrationCreatesEmptyFoundationWithoutBackfill() {
        assertThat(jdbc.queryForObject("select to_regclass('public.capture_requests')::text", String.class))
                .isEqualTo("capture_requests");
        assertThat(jdbc.queryForObject("select count(*) from capture_requests", Long.class)).isZero();
    }

    record Fixture(long payment, long transaction, String flow, String key) {}
    Fixture fixture() {
        String suffix=UUID.randomUUID().toString();
        long user=jdbc.queryForObject("insert into users(username,password,created_at,updated_at) values(?, 'unused', localtimestamp,localtimestamp) returning id",Long.class,"request-"+suffix);
        long order=jdbc.queryForObject("insert into orders(user_id,total_amount,change_deadline_at) values(?,5500,localtimestamp) returning id",Long.class,user);
        String flow="pfw_"+suffix, key="capture-"+suffix;
        long payment=jdbc.queryForObject("insert into payments(order_id,provider,payment_method,status,amount,provider_payment_id) values(?,'PAYJP','CARD','AUTHORIZED',5500,?) returning id",Long.class,order,flow);
        long transaction=jdbc.queryForObject("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key) values(?,'CAPTURE','PENDING',5500,0,?) returning id",Long.class,payment,key);
        return new Fixture(payment,transaction,flow,key);
    }
    LinkedHashMap<String,Object> values(Fixture f) {
        var r=CaptureHttpRequest.create(new CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp"),f.flow(),5500,f.key());
        var v=new LinkedHashMap<String,Object>();
        v.put("payment_id",f.payment());v.put("capture_transaction_id",f.transaction());v.put("transaction_type","CAPTURE");
        v.put("provider","PAYJP");v.put("payment_method","CARD");v.put("provider_flow_id",f.flow());
        v.put("connection_id","primary");v.put("provider_account_ref","merchant");v.put("mode","TEST");v.put("api_origin","https://api.pay.jp");
        v.put("http_method",r.method());v.put("api_path",r.path());v.put("content_type",r.contentType());
        v.put("amount_to_capture",5500);v.put("order_content_revision",0);v.put("idempotency_key",f.key());
        v.put("body_format_version",1);v.put("request_body",r.body());v.put("fingerprint_version",1);v.put("request_fingerprint",r.fingerprint());
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        v.put("created_at",java.time.OffsetDateTime.ofInstant(now,java.time.ZoneOffset.UTC));
        v.put("replay_not_after",java.time.OffsetDateTime.ofInstant(now.plus(23,ChronoUnit.HOURS),java.time.ZoneOffset.UTC));
        return v;
    }
    long insert(Map<String,Object> v) {
        String columns=String.join(",",v.keySet());String marks=String.join(",",Collections.nCopies(v.size(),"?"));
        return jdbc.queryForObject("insert into capture_requests("+columns+") values("+marks+") returning id",Long.class,v.values().toArray());
    }
    void rejects(Runnable operation,String state) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            var savepoint=connection.setSavepoint();
            try {
                Throwable failure=catchThrowable(operation::run);assertThat(failure).isNotNull();
                while(!(failure instanceof SQLException)&&failure.getCause()!=null)failure=failure.getCause();
                assertThat(failure).isInstanceOf(SQLException.class);
                assertThat(((SQLException)failure).getSQLState()).isEqualTo(state);
            } finally { connection.rollback(savepoint);connection.releaseSavepoint(savepoint); }
            return null;
        });
    }
    @Test void savesExactBytesAndRestoresValidatedRequest() {
        var f=fixture();var v=values(f);long id=insert(v);
        var row=jdbc.queryForMap("select * from capture_requests where id=?",id);
        var restored=CaptureHttpRequest.restore(new CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp"),f.flow(),5500,f.key(),
                (String)row.get("http_method"),(String)row.get("api_path"),(String)row.get("content_type"),1,(byte[])row.get("request_body"),1,(byte[])row.get("request_fingerprint"));
        assertThat(restored.body()).isEqualTo((byte[])v.get("request_body"));
        assertThat(jdbc.queryForObject("select created_at from capture_requests where id=?",java.time.OffsetDateTime.class,id)).isEqualTo(v.get("created_at"));
    }
    @ParameterizedTest @ValueSource(strings={"flow","amount","key","revision","payment","transaction-type","provider","method","path","content-type","body","body-version","hash-version","hash","mode","origin","connection","account","null","deadline-equal","deadline-late","flow-shape","amount-mismatch","key-mismatch","revision-mismatch"})
    void rejectsInvalidRows(String field) {
        var f=fixture();var v=values(f);
        switch(field) {
            case "flow-shape" -> {v.put("provider_flow_id","invalid");v.put("api_path","/v2/payment_flows/invalid/capture");}
            case "amount-mismatch" -> {v.put("amount_to_capture",5501);v.put("request_body","{\"amount_to_capture\":5501}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            case "key-mismatch" -> v.put("idempotency_key","other-key");
            case "revision-mismatch" -> v.put("order_content_revision",1);
            case "flow" -> {v.put("provider_flow_id","pfw_other");v.put("api_path","/v2/payment_flows/pfw_other/capture");}
            case "amount" -> v.put("amount_to_capture",0);
            case "key" -> v.put("idempotency_key","bad/key");
            case "revision" -> v.put("order_content_revision",-1);
            case "payment" -> v.put("payment_id",fixture().payment());
            case "transaction-type" -> v.put("transaction_type","AUTHORIZE");
            case "provider" -> v.put("provider","MANUAL");
            case "method" -> v.put("http_method","GET");
            case "path" -> v.put("api_path","/wrong");
            case "content-type" -> v.put("content_type","text/plain");
            case "body" -> v.put("request_body","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "body-version" -> v.put("body_format_version",2);
            case "hash-version" -> v.put("fingerprint_version",2);
            case "hash" -> v.put("request_fingerprint",new byte[31]);
            case "mode" -> v.put("mode","UNKNOWN");
            case "origin" -> v.put("api_origin","http://api.pay.jp");
            case "connection" -> v.put("connection_id"," ");
            case "account" -> v.put("provider_account_ref","");
            case "null" -> v.put("request_body",null);
            case "deadline-equal" -> v.put("replay_not_after",v.get("created_at"));
            case "deadline-late" -> v.put("replay_not_after",((java.time.OffsetDateTime)v.get("created_at")).plusHours(24));
        }
        String state=Set.of("flow","payment","amount-mismatch","key-mismatch","revision-mismatch").contains(field)?"23503":field.equals("null")?"23502":"23514";
        rejects(()->insert(v),state);
    }
    @Test void rejectsDuplicateCaptureAndMissingParent() {
        var v=values(fixture());long id=insert(v);
        rejects(()->insert(v),"23505");
        var other=values(fixture());other.put("capture_transaction_id",Long.MAX_VALUE);
        rejects(()->insert(other),"23503");
        other.put("id",id);rejects(()->insert(other),"23505");
    }
    @ParameterizedTest @ValueSource(strings={"update","delete","truncate"})
    void rejectsMutationEvenWithApplicationSetFlags(String action) {
        long id=insert(values(fixture()));
        jdbc.execute("set local ecsite.capture_delete_allowed = 'true'");
        String sql=switch(action){case "update" -> "update capture_requests set connection_id=connection_id where id="+id;
            case "delete" -> "delete from capture_requests where id="+id;default -> "truncate capture_requests";};
        rejects(()->jdbc.execute(sql),"55000");
        assertThat(jdbc.queryForObject("select count(*) from capture_requests where id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void allowsExistingStatusUpdatesButLocksRequestIdentity() {
        var f=fixture();insert(values(f));
        jdbc.update("update payments set status='CAPTURED',updated_at=localtimestamp where id=?",f.payment());
        jdbc.update("update payment_transactions set status='SUCCEEDED',completed_at=localtimestamp where id=?",f.transaction());
        rejects(()->jdbc.update("update payments set provider_payment_id='pfw_other' where id=?",f.payment()),"23503");
        rejects(()->jdbc.update("update payment_transactions set amount=5501 where id=?",f.transaction()),"23503");
    }
    @Test void explicitRollbackLeavesNoRequestAndPreservesParents() {
        var f=fixture();
        jdbc.execute((ConnectionCallback<Void>) c->{var point=c.setSavepoint();insert(values(f));c.rollback(point);c.releaseSavepoint(point);return null;});
        assertThat(jdbc.queryForObject("select count(*) from capture_requests where capture_transaction_id=?",Long.class,f.transaction())).isZero();
        assertThat(jdbc.queryForObject("select status from payment_transactions where id=?",String.class,f.transaction())).isEqualTo("PENDING");
    }
    @Test void v53ManualResetRejectsNewSchemaBeforeDeletingAnything() throws Exception {
        var f=fixture();insert(values(f));
        jdbc.execute("create temporary table order_payment_reset_gate(expected_database text, expected_user text, expected_server_addr inet, expected_server_port int, approval text, backup_reference text) on commit drop");
        jdbc.execute("insert into order_payment_reset_gate select current_database(),current_user,inet_server_addr(),inet_server_port(),'APPROVE_ORDER_PAYMENT_RESET','isolated-test-only'");
        String sql=Files.readString(Path.of("scripts/manual/reset-order-payment-data.sql"));
        rejects(()->jdbc.execute(sql),"P0001");
        assertThat(jdbc.queryForObject("select count(*) from capture_requests where capture_transaction_id=?",Long.class,f.transaction())).isEqualTo(1);
    }

    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void migratesV53DataWithoutBackfillOrStatusRestrictions() {
        String schema="capture_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var dataSource=jdbc.getDataSource();
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("53").load().migrate();
        jdbc.execute("insert into "+schema+".users(id,username,password,created_at,updated_at) values(1,'legacy','unused',localtimestamp,localtimestamp)");
        jdbc.execute("insert into "+schema+".orders(id,user_id,total_amount,change_deadline_at) values(1,1,5500,localtimestamp)");
        jdbc.execute("insert into "+schema+".payments(id,order_id,provider,payment_method,status,amount,provider_payment_id) values(1,1,'PAYJP','CARD','AUTHORIZED',5500,'legacy-unvalidated-id')");
        jdbc.execute("insert into "+schema+".payment_transactions(id,payment_id,transaction_type,status,amount,order_content_revision,idempotency_key) values(1,1,'CAPTURE','PENDING',5500,0,'legacy/key')");
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("54").load().migrate();
        assertThat(jdbc.queryForObject("select count(*) from "+schema+".capture_requests",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select status from "+schema+".payment_transactions where id=1",String.class)).isEqualTo("PENDING");
    }
}
