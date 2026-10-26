package com.example.ecsite.payment.safety;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Container-only DDL contract. No application context, financial service or external HTTP. */
class PaymentSafetyAuditExpandPostgresTest {
    static PaymentSafetyExpandPostgresTest fixture;
    static JdbcTemplate db;
    @BeforeAll static void containerSchema() {
        PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();
        fixture = new PaymentSafetyExpandPostgresTest();
        fixture.isolatedSchema();
        fixture.migrate("56");
        db = fixture.jdbc;
        db.setQueryTimeout(30);
    }
    @Test void freshMigrationCreatesOnlyFourAuditTables() {
        assertThat(db.queryForList("select table_name from information_schema.tables where table_schema=? and table_name like 'payment_safety_%' order by table_name", String.class, fixture.schema))
            .containsExactly("payment_safety_assessments", "payment_safety_audit_events", "payment_safety_classification_runs", "payment_safety_review_cases");
    }

    static final String HASH = "ab".repeat(32);
    static final String RUNS="payment_safety_classification_runs", ASS="payment_safety_assessments", CASES="payment_safety_review_cases", EVENTS="payment_safety_audit_events";
    UUID run() {
        UUID id=UUID.randomUUID();
        db.update("insert into "+RUNS+"(id,request_digest,execution_type,mode,guard_activation_ref,classification_contract_version,classifier_impl_version,fingerprint_format_version,contract_manifest_digest,contract_manifest,scope_kind,lower_order_id_exclusive,upper_order_id_inclusive,cursor_order_id,status,lease_owner,lease_epoch,lease_until,heartbeat_at,requested_by_type,requested_by_username) values (?,?,'DIAGNOSTIC','ENFORCED','activation-test','v1','impl1',1,?,'{\"version\":1}', 'RANGE',0,1000000,0,'RUNNING',?,1,clock_timestamp()+interval '1 hour',clock_timestamp(),'SYSTEM','test')",id,HASH,HASH,UUID.randomUUID());
        return id;
    }
    long assessment(UUID run,long order,String phase,Long pre) {
        return db.queryForObject("insert into "+ASS+"(request_id,record_digest,order_id,run_id,attempt_no,phase,pre_assessment_id,input_fingerprint,fingerprint_format_version,classification_contract_version,classifier_impl_version,contract_manifest_digest,disposition,observed_order_status,observed_order_total_amount,observed_order_content_revision,capture_transaction_count,capture_request_count,capture_evidence_summary,payload_format_version,input_payload,classification_details,capture_evidence_details) values (?,?,?, ?,1,?,?,?,1,'v1','impl1',?,'REVIEW','ORDERED',5500,0,0,0,'NOT_APPLICABLE',1,?::jsonb,'{\"version\":1,\"reviewIssues\":[{\"code\":\"TEST\"}]}','{\"version\":1}') returning id",Long.class,UUID.randomUUID(),HASH,order,run,phase,pre,HASH,HASH,input(order));
    }
    String rawRow(Class<?> type, Map<String,String> overrides) {
        var fields=new ArrayList<String>();
        for(var field:type.getRecordComponents()) {
            String tag=switch(field.getType().getSimpleName()) {case "Long"->"int64";case "Integer"->"int32";case "Short"->"int16";case "LocalDateTime"->"timestamp";case "Instant"->"timestamptz";case "Bytes"->"bytes";default->"text";};
            fields.add("\""+field.getName()+"\":{\"type\":\""+tag+"\",\"value\":"+overrides.getOrDefault(field.getName(),"null")+"}");
        }
        return "{"+String.join(",",fields)+"}";
    }
    String input(long order) {
        return "{\"format\":\"payment-safety-input\",\"version\":1,\"facts\":{\"order\":"+rawRow(com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.OrderRow.class,Map.of("id","\""+order+"\""))+",\"payments\":[],\"transactions\":[],\"statusHistory\":[],\"captureRequests\":[],\"verificationProfiles\":[],\"timeOrigins\":[]}}";
    }
    Connection tx() throws SQLException { var c=fixture.source.getConnection();c.setAutoCommit(false);return c; }
    void exec(Connection c,String sql,Object... args) throws SQLException {
        try(var q=c.prepareStatement(sql)) { for(int n=0;n<args.length;n++)q.setObject(n+1,args[n]);q.executeUpdate(); }
    }
    long newCase(Connection c,long order,long a,Long previous) throws SQLException {
        try(var q=c.prepareStatement("insert into "+CASES+"(case_uuid,order_id,previous_case_id,first_assessment_id,latest_assessment_id,open_reasons,status,guard_activation_ref) values (?,?,?,?,?,'[\"TEST\"]','OPEN','activation-test') returning id")) {
            q.setObject(1,UUID.randomUUID());q.setLong(2,order);q.setObject(3,previous);q.setLong(4,a);q.setLong(5,a);
            try(var r=q.executeQuery()) {r.next();return r.getLong(1);}
        }
    }
    String row(Connection c,long id) throws SQLException {
        try(var q=c.prepareStatement("select ps_case_snapshot(c)::text from "+CASES+" c where id=?")) {q.setLong(1,id);try(var r=q.executeQuery()){r.next();return r.getString(1);}}
    }
    void event(Connection c,long id,String before) throws SQLException {
        exec(c,"insert into "+EVENTS+"(event_uuid,record_digest,event_scope,event_type,run_id,case_id,order_id,attempt_no,assessment_id,case_version_before,case_version_after,from_status,to_status,actor_type,actor_account_id,actor_username,occurred_at,payload_format_version,payload) select ?,?,'CASE',case when c.version=0 then case when c.previous_case_id is null then 'OPENED' else 'REOPENED' end when c.status='RESOLVED' then 'RESOLVED' else 'UPDATED' end,a.run_id,c.id,c.order_id,a.attempt_no,a.id,case when c.version=0 then null else c.version-1 end,c.version,(?::jsonb)->>'status',c.status,'ADMIN',1,'reviewer',clock_timestamp(),1,jsonb_build_object('version',1,'before',?::jsonb,'after',ps_case_snapshot(c)) from "+CASES+" c join "+ASS+" a on a.id=c.latest_assessment_id where c.id=?",UUID.randomUUID(),HASH,before,before,id);
    }
    long open(long order,long a) throws Exception {
        try(var c=tx()){long id=newCase(c,order,a,null);event(c,id,null);c.commit();return id;}
    }
    void rejects(String state, org.junit.jupiter.api.function.Executable action) {
        Throwable e=catchThrowable(action::execute);assertThat(e).isNotNull();
        while(!(e instanceof SQLException)&&e.getCause()!=null)e=e.getCause();
        assertThat(e).isInstanceOf(SQLException.class);assertThat(((SQLException)e).getSQLState()).isEqualTo(state);
    }
    @Test void upgradePreservesLegacyRowsBytesAndTriggers() {
        var f=new PaymentSafetyExpandPostgresTest();f.isolatedSchema();f.migrate("55");
        long[] legacy=f.legacyFixture();
        f.jdbc.update("update orders set active_payment_id=? where id=?",legacy[1],legacy[0]);
        f.jdbc.update("update payment_transactions set purpose='SHIPMENT_CAPTURE' where id=?",legacy[2]);
        var before=allLegacyColumns(f);
        assertThat(before.get("orders").getFirst()).contains("\"active_payment_id\": "+legacy[1]);
        assertThat(before.get("payment_transactions").getFirst()).contains("\"purpose\": \"SHIPMENT_CAPTURE\"");
        byte[] body=f.jdbc.queryForObject("select request_body from capture_requests where capture_transaction_id=?",byte[].class,legacy[2]);
        byte[] fingerprint=f.jdbc.queryForObject("select request_fingerprint from capture_requests where capture_transaction_id=?",byte[].class,legacy[2]);
        assertThat(body).isNotEmpty();
        assertThat(fingerprint).hasSize(32);
        var constraints=legacyConstraints(f);
        f.migrate("56");
        assertThat(allLegacyColumns(f)).isEqualTo(before);
        assertThat(legacyConstraints(f)).isEqualTo(constraints);
        assertThat(f.jdbc.queryForObject("select request_body from capture_requests where capture_transaction_id=?",byte[].class,legacy[2])).isEqualTo(body);
        assertThat(f.jdbc.queryForObject("select request_fingerprint from capture_requests where capture_transaction_id=?",byte[].class,legacy[2])).isEqualTo(fingerprint);
        for(String sql:List.of("update capture_requests set connection_id=connection_id","delete from capture_requests","truncate capture_requests"))f.rejects("55000",()->f.jdbc.execute(sql));
        assertThat(f.jdbc.queryForObject("select count(*) from "+ASS,Long.class)).isZero();
    }
    Map<String,List<String>> allLegacyColumns(PaymentSafetyExpandPostgresTest f) {
        var rows=new LinkedHashMap<String,List<String>>();
        // V56 starts at V55: retain every column, including purpose and active_payment_id.
        // BYTEA is represented losslessly by PostgreSQL's JSON hex strings.
        for(String table:List.of("users","orders","payments","payment_transactions","order_status_histories","capture_requests")) {
            rows.put(table,f.jdbc.queryForList("select to_jsonb(t)::text from "+table+" t order by id",String.class));
        }
        return rows;
    }
    List<Map<String,Object>> legacyConstraints(PaymentSafetyExpandPostgresTest f) {
        return f.jdbc.queryForList("select conrelid::regclass::text as relation,conname,pg_get_constraintdef(oid) as definition,convalidated from pg_constraint where connamespace=?::regnamespace and conrelid in ('users'::regclass,'orders'::regclass,'payments'::regclass,'payment_transactions'::regclass,'order_status_histories'::regclass,'capture_requests'::regclass) order by conrelid,conname",f.schema);
    }
    @Test void caseAndEventCommitTogether() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null),id=open(o,a);
        assertThat(db.queryForObject("select count(*) from "+EVENTS+" where case_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void caseWithoutEventCannotCommit() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null);
        try(var c=tx()){newCase(c,o,a,null);rejects("23514",c::commit);c.rollback();}
        assertThat(db.queryForObject("select count(*) from "+CASES+" where order_id=?",Long.class,o)).isZero();
    }
    @Test void multipleUpdatesNeedEachIntermediateEvent() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null);
        try(var c=tx()) {
            long id=newCase(c,o,a,null);event(c,id,null);
            for(int n=0;n<2;n++){String before=row(c,id);exec(c,"update "+CASES+" set version=version+1,latest_assessment_id=? where id=?",a,id);event(c,id,before);}
            c.commit();assertThat(db.queryForObject("select count(*) from "+EVENTS+" where case_id=?",Long.class,id)).isEqualTo(3);
        }
    }
    @Test void missingIntermediateEventIsRejected() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null);
        try(var c=tx()) {long id=newCase(c,o,a,null);event(c,id,null);exec(c,"update "+CASES+" set version=1 where id=?",id);String before=row(c,id);exec(c,"update "+CASES+" set version=2 where id=?",id);event(c,id,before);rejects("23514",c::commit);c.rollback();}
    }
    @Test void eventOnlyFutureVersionCannotCommit() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null),id=open(o,a);
        try(var c=tx()){exec(c,"insert into "+EVENTS+"(event_uuid,record_digest,event_scope,event_type,run_id,case_id,order_id,attempt_no,assessment_id,case_version_before,case_version_after,from_status,to_status,actor_type,actor_username,occurred_at,payload_format_version,payload) select ?,?,'CASE','UPDATED',a.run_id,c.id,c.order_id,1,a.id,0,1,'OPEN','OPEN','SYSTEM','test',clock_timestamp(),1,jsonb_build_object('version',1,'before',ps_case_snapshot(c),'after',ps_case_snapshot(c)||'{\"version\":1}'::jsonb) from "+CASES+" c join "+ASS+" a on a.id=c.latest_assessment_id where c.id=?",UUID.randomUUID(),HASH,id);rejects("23514",c::commit);c.rollback();}
    }
    @Test void mismatchingSnapshotAndVersionJumpAreRejected() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null),id=open(o,a);
        try(var c=tx()){rejects("23514",()->exec(c,"update "+CASES+" set version=version+2 where id=?",id));c.rollback();}
        try(var c=tx()){String before=row(c,id).replace("UNASSIGNED","WAITING_EXTERNAL");exec(c,"update "+CASES+" set version=version+1 where id=?",id);event(c,id,before);rejects("23514",c::commit);c.rollback();}
    }
    @Test void openDuplicateAndCrossOrderReferencesAreRejected() throws Exception {
        long o=fixture.order(),other=fixture.order(),a=assessment(run(),o,"OBSERVED",null);open(o,a);
        try(var c=tx()){rejects("23505",()->newCase(c,o,a,null));c.rollback();}
        try(var c=tx()){rejects("23503",()->newCase(c,other,a,null));c.rollback();}
    }
    @Test void preApplyRequiresSameRunOrderAttemptAndPhase() {
        UUID r=run();long o=fixture.order(),a=assessment(r,o,"PRE_APPLY",null);
        assessment(r,o,"POST_APPLY",a);
        rejects("23503",()->assessment(run(),o,"POST_APPLY",a));
        long observed=assessment(run(),fixture.order(),"OBSERVED",null);
        rejects("23514",()->assessment(run(),fixture.order(),"POST_APPLY",observed));
        rejects("23514",()->assessment(run(),fixture.order(),"POST_APPLY",null));
    }
    @Test void immutableTablesRejectEvenEmptyMutations() {
        for(String table:List.of(ASS,EVENTS))for(String operation:List.of("update "+table+" set record_digest=record_digest where false","delete from "+table+" where false","truncate "+table+" cascade"))rejects("55000",()->db.execute(operation));
    }
    @Test void runStateLeaseAndImmutableHeaderAreValidated() {
        UUID r=run();
        rejects("23514",()->db.update("update "+RUNS+" set lease_owner=null,version=version+1 where id=?",r));
        rejects("23514",()->db.update("update "+RUNS+" set classifier_impl_version='other',version=version+1 where id=?",r));
        rejects("23514",()->db.update("update "+RUNS+" set lease_owner=?,version=version+1 where id=?",UUID.randomUUID(),r));
        db.update("update "+RUNS+" set lease_until=clock_timestamp()-interval '1 second',version=version+1 where id=?",r);
        assertThat(db.update("update "+RUNS+" set lease_owner=?,lease_epoch=lease_epoch+1,lease_until=clock_timestamp()+interval '1 hour',version=version+1 where id=? and lease_epoch=1",UUID.randomUUID(),r)).isEqualTo(1);
        assertThat(db.update("update "+RUNS+" set version=version+1 where id=? and lease_epoch=1",r)).isZero();
        db.update("update "+RUNS+" set status='PAUSED',lease_owner=null,lease_until=null,version=version+1 where id=?",r);
        rejects("23514",()->db.update("update "+RUNS+" set status='SUCCESS',finished_at=clock_timestamp(),version=version+1 where id=?",r));
    }
    @Test void deliberateLateDdlConflictRollsBackEverything() {
        var f=new PaymentSafetyExpandPostgresTest();f.isolatedSchema();f.migrate("55");f.legacyFixture();var before=allLegacyColumns(f);
        f.jdbc.execute("create function ps_require_event_case_change() returns trigger language plpgsql as 'begin return new; end'");
        assertThatThrownBy(()->f.migrate("56")).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(f.jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=? and table_name like 'payment_safety_%'",Long.class,f.schema)).isZero();
        assertThat(f.jdbc.queryForObject("select count(*) from flyway_schema_history where version='56'",Long.class)).isZero();assertThat(allLegacyColumns(f)).isEqualTo(before);
    }

    long cloneAssessment(long source,String patch) {
        return db.queryForObject("insert into "+ASS+" select (jsonb_populate_record(null::"+ASS+",to_jsonb(a)||jsonb_build_object('id',nextval(pg_get_serial_sequence('"+ASS+"','id')),'request_id',?::uuid,'attempt_no',a.attempt_no+1)||?::jsonb)).* from "+ASS+" a where id=? returning id",Long.class,UUID.randomUUID(),patch,source);
    }
    @Test void selectedPaymentOwnershipAndNullBypassAreRejected() {
        long o=fixture.order(),other=fixture.order(),a=assessment(run(),o,"OBSERVED",null),p=fixture.payment(other);
        String selected="{\"disposition\":\"SELECTED\",\"classification_details\":{\"version\":1,\"reviewIssues\":[]},\"selected_payment_id\":"+p+"}";
        rejects("23503",()->cloneAssessment(a,selected));
        rejects("23514",()->cloneAssessment(a,selected.replace("\"selected_payment_id\":"+p,"\"selected_payment_id\":null")));
        rejects("23502",()->cloneAssessment(a,"{\"order_id\":null}"));
        long own=fixture.payment(o);
        cloneAssessment(a,selected.replace("\"selected_payment_id\":"+p,"\"selected_payment_id\":"+own));
    }
    @Test void assessmentForeignRunOrderAndAttemptCannotBeBypassed() {
        UUID r=run();long o=fixture.order(),a=assessment(r,o,"PRE_APPLY",null);
        rejects("23503",()->cloneAssessment(a,"{\"phase\":\"POST_APPLY\",\"pre_assessment_id\":"+a+"}")); // attempt differs
        rejects("23514",()->cloneAssessment(a,"{\"phase\":\"POST_APPLY\",\"pre_assessment_id\":null}"));
        rejects("23502",()->cloneAssessment(a,"{\"run_id\":null}"));
    }
    void runEvent(UUID r,long target,int attempt,String type,String outcome) {
        db.update("insert into "+EVENTS+"(event_uuid,record_digest,event_scope,event_type,run_id,target_order_id,attempt_no,lease_epoch,outcome,actor_type,actor_username,occurred_at,payload_format_version,payload) values (?,?,'RUN',?,?,?,?,1,?,'SYSTEM','test',clock_timestamp(),1,'{\"version\":1}')",UUID.randomUUID(),HASH,type,r,target,attempt,outcome);
    }
    @Test void startedAndFinishedCoexistButDuplicateFinishAndAcceptedOrderDoNot() {
        UUID r=run();long o=fixture.order();
        runEvent(r,o,1,"ORDER_STARTED",null);runEvent(r,o,1,"ORDER_FINISHED","ACQUISITION_FAILED");
        rejects("23505",()->runEvent(r,o,1,"ORDER_FINISHED","NOT_FOUND"));
        runEvent(r,o,2,"ORDER_STARTED",null);runEvent(r,o,2,"ORDER_FINISHED","NOT_FOUND");
        rejects("23505",()->runEvent(r,o,3,"ORDER_FINISHED","SKIPPED_UNCHANGED"));
        assertThat(db.queryForObject("select count(*) from "+EVENTS+" where run_id=?",Long.class,r)).isEqualTo(4);
    }
    @Test void eventMandatoryOwnershipColumnsCannotUseMatchSimpleNullEscape() {
        UUID r=run();long o=fixture.order();
        rejects("23514",()->runEvent(r,o,1,"ORDER_FINISHED","CLASSIFIED"));
        rejects("23514",()->db.update("insert into "+EVENTS+"(event_uuid,record_digest,event_scope,event_type,run_id,case_id,actor_type,actor_username,occurred_at,payload_format_version,payload) values (?,?,'CASE','OPENED',?,1,'SYSTEM','test',clock_timestamp(),1,'{\"version\":1,\"before\":null,\"after\":{}}')",UUID.randomUUID(),HASH,r));
    }
    @Test void resolvedCaseIsImmutableAndReopenUsesResolvedPredecessor() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null),id=open(o,a);
        try(var c=tx()){rejects("23514",()->newCase(c,o,a,id));c.rollback();}
        try(var c=tx()) {
            String before=row(c,id);
            exec(c,"update "+CASES+" set version=version+1,status='RESOLVED',handling_status='COMPLETED',resolved_at=clock_timestamp(),resolved_by_account_id=1,resolved_by_username='reviewer',resolution_code='CONFIRMED',resolution_comment='Evidence checked' where id=?",id);
            event(c,id,before);c.commit();
        }
        rejects("55000",()->db.update("update "+CASES+" set version=version+1 where id=?",id));
        try(var c=tx()){long next=newCase(c,o,a,id);event(c,next,null);c.commit();}
        try(var c=tx()){rejects("23505",()->newCase(c,o,a,id));c.rollback();}
    }
    @Test void optimisticVersionMakesStaleUpdateAffectNoRows() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null),id=open(o,a);
        try(var first=tx();var second=tx()) {
            String before=row(first,id);assertThat(updateVersion(first,id,0)).isEqualTo(1);event(first,id,before);first.commit();
            assertThat(updateVersion(second,id,0)).isZero();second.commit();
        }
    }
    int updateVersion(Connection c,long id,int version) throws SQLException {
        try(var q=c.prepareStatement("update "+CASES+" set version=version+1 where id=? and version=?")){q.setLong(1,id);q.setInt(2,version);return q.executeUpdate();}
    }
    @Test void immediateConstraintCheckAndPastEventDuplicateAreRejected() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null),id=open(o,a);
        try(var c=tx()){rejects("23505",()->event(c,id,null));c.rollback();}
        try(var c=tx()){exec(c,"update "+CASES+" set version=version+1 where id=?",id);rejects("23514",()->exec(c,"set constraints all immediate"));c.rollback();}
    }
    @Test void typedPayloadRetainsNullUnknownDuplicatesOrderTimestampsAndBytes() {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null);
        String payment=rawRow(com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.PaymentRow.class,Map.of("provider","\" Raw UNKNOWN \"","id","\"9007199254740993\"","createdAt","\"2026-10-20T13:59:59.123456789\""));
        String capture=rawRow(com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.CaptureRow.class,Map.of("requestBody","\"AAEC/w==\"","requestFingerprint","\"/wA=\"","createdAt","{\"epochSecond\":\"1792472400\",\"nano\":123456789}"));
        String payload=input(o).replace("\"payments\":[]","\"payments\":["+payment+","+payment+"]").replace("\"captureRequests\":[]","\"captureRequests\":["+capture+"]");
        long b=cloneAssessment(a,"{\"input_payload\":"+payload+",\"record_digest\":\""+"cd".repeat(32)+"\"}");
        var actual=db.queryForMap("select input_payload#>>'{facts,payments,0,id,value}' as id,input_payload#>>'{facts,payments,1,provider,value}' as provider,jsonb_array_length(input_payload#>'{facts,payments}') as count,input_payload#>>'{facts,captureRequests,0,requestBody,value}' as body,input_payload#>'{facts,order,status,value}' as null_value,record_digest,input_fingerprint from "+ASS+" where id=?",b);
        assertThat(actual.get("id")).isEqualTo("9007199254740993");assertThat(actual.get("provider")).isEqualTo(" Raw UNKNOWN ");assertThat(actual.get("count")).isEqualTo(2);
        assertThat(Base64.getDecoder().decode((String)actual.get("body"))).containsExactly((byte)0,(byte)1,(byte)2,(byte)255);
        assertThat(actual.get("null_value").toString()).isEqualTo("null");assertThat(actual.get("record_digest")).isNotEqualTo(actual.get("input_fingerprint"));
        assertThat(db.queryForObject("select input_payload=?::jsonb from "+ASS+" where id=?",Boolean.class,payload,b)).isTrue();
    }
    @Test void missingKeysWrongTypesAndSqlNullAreRejected() {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null);
        for(String payload:List.of("{}",input(o).replace("\"payments\":[]","\"payments\":null"),input(o).replace("\"type\":\"int64\"","\"type\":\"text\""),input(o).replace("\"id\":{\"type\":\"int64\",\"value\":\""+o+"\"},",""))) {
            rejects("23514",()->cloneAssessment(a,"{\"input_payload\":"+payload+"}"));
        }
        rejects("23502",()->cloneAssessment(a,"{\"input_payload\":null}"));
        rejects("23514",()->cloneAssessment(a,"{\"financial_resumption\":\"ALLOWED\"}"));
    }
    @Test void payloadSizesFailRatherThanTruncate() {
        UUID r=run();
        rejects("23514",()->db.update("insert into "+EVENTS+"(event_uuid,record_digest,event_scope,event_type,run_id,actor_type,actor_username,occurred_at,payload_format_version,payload) values (?,?,'RUN','STARTED',?,'SYSTEM','test',clock_timestamp(),1,jsonb_build_object('version',1,'data',repeat('x',262144)))",UUID.randomUUID(),HASH,r));
        long a=assessment(r,fixture.order(),"OBSERVED",null);
        var columns=db.queryForList("select column_name from information_schema.columns where table_schema=? and table_name=? order by ordinal_position",String.class,fixture.schema,ASS);
        var expressions=new ArrayList<String>();
        for(String col:columns)expressions.add(switch(col) {
            case "id"->"nextval(pg_get_serial_sequence('"+ASS+"','id'))";
            case "request_id"->"?::uuid";
            case "attempt_no"->"2";
            case "classification_details"->"jsonb_build_object('version',1,'reviewIssues',jsonb_build_array('TEST'),'large',repeat(chr(1),22369622))";
            default->"a."+col;
        });
        // A 21.4 MiB raw control-character value serializes above 128 MiB; no giant
        // jsonb_populate_record reserialization and no huge parameter or payload in errors.
        db.setQueryTimeout(30);
        try { rejects("23514",()->db.update("insert into "+ASS+"("+String.join(",",columns)+") select "+String.join(",",expressions)+" from "+ASS+" a where id=?",UUID.randomUUID(),a)); }
        finally { db.setQueryTimeout(0); }
        assertThat(db.queryForObject("select count(*) from "+ASS+" where run_id=?",Long.class,r)).isEqualTo(1);
    }

    @Test void signedExpandedAndInfinityMappedLocalYearsAreRepresentable() {
        for(String timestamp:List.of("+10000-01-01T00:00:00","-0001-01-01T00:00:00","+999999999-12-31T23:59:59.999999999","-999999999-01-01T00:00:00")) {
            assertThat(db.queryForObject("select ps_valid_typed_value(jsonb_build_object('type','timestamp','value',?::text))",Boolean.class,timestamp)).isTrue();
        }
    }

    @Test void runCanResumeWithNewEpochAndTerminalStateCannotChange() {
        UUID r=run(),owner=UUID.randomUUID();
        db.update("update "+RUNS+" set status='PAUSED',lease_owner=null,lease_until=null,version=1 where id=?",r);
        rejects("23514",()->db.update("update "+RUNS+" set status='RUNNING',lease_owner=?,lease_until=clock_timestamp()+interval '1 hour',version=2 where id=?",owner,r));
        db.update("update "+RUNS+" set status='RUNNING',lease_owner=?,lease_epoch=2,lease_until=clock_timestamp()+interval '1 hour',heartbeat_at=clock_timestamp(),version=2 where id=?",owner,r);
        db.update("update "+RUNS+" set status='SUCCESS',cursor_order_id=upper_order_id_inclusive,lease_owner=null,lease_until=null,finished_at=clock_timestamp(),version=3 where id=?",r);
        rejects("23514",()->db.update("update "+RUNS+" set version=4 where id=?",r));
    }
    @Test void postApplyWrongOrderWithSameRunAndAttemptIsRejected() {
        UUID r=run();long a=assessment(r,fixture.order(),"PRE_APPLY",null);
        rejects("23503",()->assessment(r,fixture.order(),"POST_APPLY",a));
    }
    @Test void resolveAndReopenWithinOneTransactionKeepBothChains() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null);
        try(var c=tx()) {
            long id=newCase(c,o,a,null);event(c,id,null);String before=row(c,id);
            exec(c,"update "+CASES+" set version=1,status='RESOLVED',handling_status='COMPLETED',resolved_at=clock_timestamp(),resolved_by_account_id=1,resolved_by_username='reviewer',resolution_code='CONFIRMED',resolution_comment='Checked' where id=?",id);event(c,id,before);
            long next=newCase(c,o,a,id);event(c,next,null);c.commit();
        }
        assertThat(db.queryForObject("select count(*) from "+EVENTS+" where order_id=?",Long.class,o)).isEqualTo(3);
    }
    @Test void realConcurrentVersionUpdateWaitsThenRejectsStaleVersion() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null),id=open(o,a);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var first=tx();var second=tx()) {
            String before=row(first,id);updateVersion(first,id,0);event(first,id,before);
            int pid;try(var q=second.createStatement();var r=q.executeQuery("select pg_backend_pid()")){r.next();pid=r.getInt(1);}
            var future=executor.submit(()->updateVersion(second,id,0));
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean blocked=false;
            while(System.nanoTime()<until) {
                blocked=Boolean.TRUE.equals(db.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid));
                if(blocked)break;Thread.sleep(10);
            }
            assertThat(blocked).isTrue();first.commit();
            assertThat(future.get(5,java.util.concurrent.TimeUnit.SECONDS)).isZero();second.commit();
        } finally {executor.shutdownNow();}
    }
    @Test void heterogeneousArrayOrderAndNonemptyProfilesArePreserved() {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null);
        String p1=rawRow(com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.PaymentRow.class,Map.of("id","\"2\""));
        String p2=rawRow(com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.PaymentRow.class,Map.of("id","\"1\""));
        String payload=input(o).replace("\"payments\":[]","\"payments\":["+p1+","+p2+","+p1+"]")
            .replace("\"verificationProfiles\":[]","\"verificationProfiles\":[{\"key\":{\"type\":\"text\",\"value\":\"manifest\"},\"value\":{\"type\":\"text\",\"value\":\"approved-ref\"}}]")
            .replace("\"timeOrigins\":[]","\"timeOrigins\":[{\"key\":{\"field\":{\"type\":\"enum\",\"value\":\"ORDER_SHIPPED_AT\"},\"rowId\":{\"type\":\"int64\",\"value\":\"1\"}},\"value\":{\"kind\":{\"type\":\"enum\",\"value\":\"UNKNOWN\"},\"clockDomain\":{\"type\":\"text\",\"value\":null},\"zoneId\":{\"type\":\"text\",\"value\":null}}}]");
        long b=cloneAssessment(a,"{\"input_payload\":"+payload+"}");
        assertThat(db.queryForList("select v#>>'{id,value}' from "+ASS+",jsonb_array_elements(input_payload#>'{facts,payments}') with ordinality t(v,n) where id=? order by n",String.class,b)).containsExactly("2","1","2");
        assertThat(db.queryForObject("select input_payload=?::jsonb from "+ASS+" where id=?",Boolean.class,payload,b)).isTrue();
    }

    @Test void eventAssessmentOwnerRunAndAttemptCannotBeMixed() throws Exception {
        UUID r=run();long o=fixture.order(),other=fixture.order(),a=assessment(r,o,"OBSERVED",null),id=open(o,a);
        long foreignAssessment=assessment(run(),o,"OBSERVED",null);
        for(String patch:List.of("{\"order_id\":"+other+"}","{\"run_id\":\""+run()+"\"}","{\"attempt_no\":2}","{\"assessment_id\":"+foreignAssessment+"}")) {
            rejects("23503",()->db.update("insert into "+EVENTS+" select (jsonb_populate_record(null::"+EVENTS+",to_jsonb(e)||jsonb_build_object('id',nextval(pg_get_serial_sequence('"+EVENTS+"','id')),'event_uuid',?::uuid,'event_type','UPDATED','case_version_before',0,'case_version_after',1,'from_status','OPEN','payload',jsonb_build_object('version',1,'before',payload->'after','after',(payload->'after')||'{\"version\":1}'::jsonb))||?::jsonb)).* from "+EVENTS+" e where case_id=? and case_version_after=0",UUID.randomUUID(),patch,id));
        }
    }
    @Test void unknownPurposeStatusAndNullableTransactionFieldsAreStoredRaw() {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null);
        String t=rawRow(com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.TransactionRow.class,Map.of("transactionType","\" FUTURE_TYPE \"","status","\"UNKNOWN\"","purpose","\"LEGACY_UNKNOWN\""));
        String payload=input(o).replace("\"transactions\":[]","\"transactions\":["+t+"]");
        long b=cloneAssessment(a,"{\"input_payload\":"+payload+"}");
        assertThat(db.queryForObject("select input_payload#>>'{facts,transactions,0,purpose,value}' from "+ASS+" where id=?",String.class,b)).isEqualTo("LEGACY_UNKNOWN");
        assertThat(db.queryForObject("select input_payload#>'{facts,transactions,0,completedAt,value}'='null'::jsonb from "+ASS+" where id=?",Boolean.class,b)).isTrue();
    }
    @Test void observationOnlyAssessmentCannotCreateAnOpenCase() throws Exception {
        UUID original=run(),observe=UUID.randomUUID();
        db.update("insert into "+RUNS+" select (jsonb_populate_record(null::"+RUNS+",to_jsonb(r)||jsonb_build_object('id',?::uuid,'mode','OBSERVE_ONLY','guard_activation_ref',null))).* from "+RUNS+" r where id=?",observe,original);
        long o=fixture.order(),a=assessment(observe,o,"OBSERVED",null);
        try(var c=tx()){rejects("23514",()->newCase(c,o,a,null));c.rollback();}
    }

    @Test void caseEventChainSurvivesDifferentSessionTimeZones() throws Exception {
        long o=fixture.order(),a=assessment(run(),o,"OBSERVED",null),id;
        try(var c=tx()){exec(c,"set local time zone 'UTC'");id=newCase(c,o,a,null);event(c,id,null);c.commit();}
        try(var c=tx()) {exec(c,"set local time zone 'Asia/Tokyo'");String before=row(c,id);exec(c,"update "+CASES+" set version=version+1 where id=?",id);event(c,id,before);c.commit();}
    }
}
