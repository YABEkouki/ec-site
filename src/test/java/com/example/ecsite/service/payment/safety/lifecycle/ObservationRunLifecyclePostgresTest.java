package com.example.ecsite.service.payment.safety.lifecycle;

import com.example.ecsite.service.payment.safety.payload.*;
import com.example.ecsite.service.payment.safety.payload.ObservationLifecycleCommands.*;
import com.example.ecsite.service.payment.safety.CaptureEvidenceProfiles;
import com.example.ecsite.support.isolation.IsolatedDatabaseGuard;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.payment.safety.lifecycle.ObservationRunReceipt.Status.*;

/** Run only via the isolated postgres:17 launcher. Guard precedes DataSource/Flyway/JDBC access. */
class ObservationRunLifecyclePostgresTest {
    static PGSimpleDataSource source;
    static JdbcTemplate db;
    ObservationRunLifecycle api=new ObservationRunLifecycle(source);
    UUID owner=UUID.randomUUID();
    ObservationRunRequest request=request(false);
    static ObservationRunRequest request(boolean empty) {
        return new ObservationRunRequest(UUID.randomUUID(),ObservationRunRequest.ExecutionType.DIAGNOSTIC,
            new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.range(0,1000),empty?List.of():List.of(1L,10L),"v1","impl1",(short)1,CaptureEvidenceProfiles.none(),Map.of(),Map.of(),ObservationRunRequest.Policy.defaults()),
            ObservationRunRequest.Actor.system("isolated-observer"));
    }
    @BeforeAll static void isolated() {
        String url=System.getenv("EC_TEST_DB_URL"),user=System.getenv("EC_TEST_DB_USER"),password=System.getenv("EC_TEST_DB_PASSWORD");
        var env=new MockEnvironment();for(String prefix:List.of("spring.datasource","spring.flyway")) {
            env.setProperty(prefix+".url",Objects.toString(url,""));env.setProperty(prefix+(prefix.endsWith("flyway")?".user":".username"),Objects.toString(user,""));env.setProperty(prefix+".password",Objects.toString(password,""));
        }
        IsolatedDatabaseGuard.verify(env);
        source=new PGSimpleDataSource();source.setURL(url);source.setUser(user);source.setPassword(password);source.setCurrentSchema("lifecycle_"+UUID.randomUUID().toString().replace("-",""));
        Flyway.configure().dataSource(source).defaultSchema(source.getCurrentSchema()).target("56").load().migrate();db=new JdbcTemplate(source);db.setQueryTimeout(10);
    }
    Command create(){return api.prepareCreate(request,owner);}
    Command command(Action action,ObservationRunReceipt.RunView v,UUID after) {
        return api.prepare(request,action,UUID.randomUUID(),v.owner(),after,v.epoch(),v.version(),request.requester());
    }
    ObservationRunReceipt.RunView created(){var r=api.execute(create());assertThat(r.status()).isEqualTo(APPLIED);return r.current();}
    long events(){return db.queryForObject("select count(*) from payment_safety_audit_events where run_id=?",Long.class,request.requestId());}
    long runs(){return db.queryForObject("select count(*) from payment_safety_classification_runs where id=?",Long.class,request.requestId());}
    void expire() {db.update("update payment_safety_classification_runs set lease_until=clock_timestamp()-interval '1 second',version=version+1 where id=?",request.requestId());}
    Connection tx()throws SQLException{var c=source.getConnection();ObservationRunLifecycle.configure(c,request.manifest().policy());return c;}
    @Test void createIsAtomicIdempotentAndBindsOwnerAndRequest() {
        var c=create();assertThat(api.execute(c).status()).isEqualTo(APPLIED);assertThat(api.execute(c).status()).isEqualTo(REPLAYED);
        assertThat(events()).isEqualTo(1);assertThat(runs()).isEqualTo(1);
        assertThat(api.execute(api.prepareCreate(request,UUID.randomUUID())).status()).isEqualTo(CONTENT_CONFLICT);
        var changed=new ObservationRunRequest(request.requestId(),request.executionType(),request.manifest(),ObservationRunRequest.Actor.system("other"));
        assertThat(api.execute(api.prepareCreate(changed,owner)).status()).isEqualTo(CONTENT_CONFLICT);
        var row=db.queryForMap("select status,version,lease_epoch,cursor_order_id,guard_activation_ref,mode from payment_safety_classification_runs where id=?",request.requestId());
        assertThat(row).containsEntry("status","RUNNING").containsEntry("version",0L).containsEntry("lease_epoch",1L).containsEntry("cursor_order_id",0L).containsEntry("guard_activation_ref",null).containsEntry("mode","OBSERVE_ONLY");
    }
    @Test void heartbeatHasNoEventAndRequiresExactCasAndOwner() {
        var v=created();var h=command(Action.HEARTBEAT,v,owner);var changed=api.execute(h);
        assertThat(changed.status()).isEqualTo(APPLIED);assertThat(changed.current().version()).isEqualTo(1);assertThat(changed.current().epoch()).isEqualTo(1);assertThat(events()).isEqualTo(1);
        assertThat(api.execute(h).status()).isEqualTo(VERSION_CONFLICT);assertThat(api.resolve(h).status()).isEqualTo(OBSERVED);
        var badOwner=UUID.randomUUID();var wrong=api.prepare(request,Action.HEARTBEAT,UUID.randomUUID(),badOwner,badOwner,1,1,request.requester());
        assertThat(api.execute(wrong).status()).isEqualTo(LEASE_LOST);
    }
    @Test void pauseResumeAndHistoricalReplayDoNotRestoreOldAuthority() {
        var v=created();var pause=command(Action.PAUSE,v,null);var p=api.execute(pause);
        assertThat(p.status()).isEqualTo(APPLIED);assertThat(p.current().status()).isEqualTo("PAUSED");assertThat(p.current().owner()).isNull();assertThat(p.current().leaseUntil()).isNull();assertThat(p.current().epoch()).isEqualTo(1);
        UUID next=UUID.randomUUID();var resumed=api.execute(command(Action.RESUME,p.current(),next));assertThat(resumed.status()).isEqualTo(APPLIED);assertThat(resumed.current().epoch()).isEqualTo(2);assertThat(resumed.current().version()).isEqualTo(2);
        var replay=api.execute(pause);assertThat(replay.status()).isEqualTo(REPLAYED);assertThat(replay.current().owner()).isEqualTo(next);assertThat(replay.current().epoch()).isEqualTo(2);
        assertThat(api.execute(command(Action.HEARTBEAT,v,owner)).status()).isEqualTo(LEASE_LOST);assertThat(events()).isEqualTo(3);
    }
    @Test void expiredLeaseRejectsHeartbeatPauseAndFailureThenAllowsTakeover() {
        var v=created();expire();var old=api.resolve(command(Action.HEARTBEAT,v,owner)).current();
        assertThat(api.execute(command(Action.HEARTBEAT,old,owner)).status()).isEqualTo(LEASE_LOST);
        assertThat(api.execute(command(Action.PAUSE,old,null)).status()).isEqualTo(LEASE_LOST);
        assertThat(api.execute(command(Action.BEFORE_OBSERVATION_FAILED,old,null)).status()).isEqualTo(LEASE_LOST);
        var t=api.execute(command(Action.TAKEOVER,old,UUID.randomUUID()));assertThat(t.status()).isEqualTo(APPLIED);assertThat(t.current().epoch()).isEqualTo(2);assertThat(t.current().version()).isEqualTo(2);
        assertThat(api.execute(command(Action.HEARTBEAT,old,owner)).status()).isEqualTo(LEASE_LOST);
    }
    @Test void takeoverOfLiveLeaseIsRejected() {var v=created();assertThat(api.execute(command(Action.TAKEOVER,v,UUID.randomUUID())).status()).isEqualTo(LEASE_LOST);assertThat(events()).isEqualTo(1);}
    @Test void sameControlUuidWithDifferentCasIsContentConflict() {
        var v=created();var a=command(Action.PAUSE,v,null);assertThat(api.execute(a).status()).isEqualTo(APPLIED);
        var i=a.intent();var b=ObservationLifecycleCommands.prepare(request,Action.PAUSE,i.controlRequestId(),owner,null,1,1,i.actor(),i.occurredAt());
        assertThat(api.execute(b).status()).isEqualTo(CONTENT_CONFLICT);assertThat(events()).isEqualTo(2);
    }
    @Test void onlyEmptySuccessOrBeforeObservationFailureCanFinish() {
        var v=created();assertThat(api.execute(command(Action.EMPTY_SUCCESS,v,null)).status()).isEqualTo(UNSUPPORTED_PHASE);
        var failure=command(Action.BEFORE_OBSERVATION_FAILED,v,null);var result=api.execute(failure);assertThat(result.status()).isEqualTo(APPLIED);assertThat(result.current().status()).isEqualTo("FAILED");assertThat(result.current().cursor()).isZero();
        assertThat(api.execute(failure).status()).isEqualTo(REPLAYED);assertThat(api.execute(command(Action.HEARTBEAT,v,owner)).status()).isEqualTo(STATE_CONFLICT);
        request=request(true);v=created();var success=api.execute(command(Action.EMPTY_SUCCESS,v,null));assertThat(success.status()).isEqualTo(APPLIED);assertThat(success.current().status()).isEqualTo("SUCCESS");assertThat(success.current().cursor()).isEqualTo(1000);assertThat(success.current().owner()).isNull();
        assertThat(Arrays.stream(Action.values()).map(Enum::name)).doesNotContain("PARTIAL_FAILURE","FINISH","SUCCESS");
    }
    @Test void eventFailureRollsBackRunCreation() {
        var c=create();String function="reject_"+UUID.randomUUID().toString().replace("-","");
        db.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.run_id='"+request.requestId()+"'::uuid then raise exception using errcode='23514',message='isolated event failure'; end if; return new; end $$");
        db.execute("create trigger "+function+" before insert on payment_safety_audit_events for each row execute function "+function+"()");
        try {assertThat(api.execute(c).status()).isEqualTo(STORAGE_FAILED);assertThat(runs()).isZero();assertThat(events()).isZero();}
        finally {db.execute("drop trigger "+function+" on payment_safety_audit_events");db.execute("drop function "+function+"()");}
    }
    @Test void eventFailureRollsBackLeaseMutation() {
        var v=created();var pause=command(Action.PAUSE,v,null);
        // Test-only immutable event at the same slot, with valid digest syntax but corrupted content.
        // A trigger failure is preferable here to bypassing append-only protection.
        String function="reject_"+UUID.randomUUID().toString().replace("-","");
        db.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.event_uuid='"+pause.event().orElseThrow().eventUuid()+"'::uuid then raise exception using errcode='23514',message='isolated failure'; end if; return new; end $$");
        db.execute("create trigger "+function+" before insert on payment_safety_audit_events for each row execute function "+function+"()");
        try {assertThat(api.execute(pause).status()).isEqualTo(STORAGE_FAILED);assertThat(api.resolve(pause).status()).isEqualTo(NOT_APPLIED);assertThat(events()).isEqualTo(1);assertThat(db.queryForObject("select version from payment_safety_classification_runs where id=?",Long.class,request.requestId())).isZero();}
        finally {db.execute("drop trigger "+function+" on payment_safety_audit_events");db.execute("drop function "+function+"()");}
    }
    @Test void commitReplyLossResolvesOnANewConnectionAndDoesNotDuplicate() {
        AtomicBoolean once=new AtomicBoolean();var faulty=new ObservationRunLifecycle(wrapped((c,m,args)->{
            if(m.getName().equals("commit") && once.compareAndSet(false,true)){c.commit();throw new SQLException("simulated reply loss","08006");}return invoke(c,m,args);
        }));
        var command=faulty.prepareCreate(request,owner);var result=faulty.execute(command);
        assertThat(result.status()).isEqualTo(REPLAYED);assertThat(api.execute(command).status()).isEqualTo(REPLAYED);assertThat(events()).isEqualTo(1);
    }
    @Test void resolveTimeoutPreservesUnknownAndOriginalCommand() throws Exception {
        var v=created();var pause=command(Action.PAUSE,v,null);
        try(var c=tx();var q=c.prepareStatement("select id from payment_safety_classification_runs where id=? for no key update")) {
            q.setObject(1,request.requestId());try(var r=q.executeQuery()){assertThat(r.next()).isTrue();}
            var result=api.resolve(pause);assertThat(result.status()).isEqualTo(UNKNOWN);assertThat(result.eventUuid()).isEqualTo(pause.event().orElseThrow().eventUuid());c.rollback();
        }
        assertThat(api.resolve(pause).status()).isEqualTo(NOT_APPLIED);assertThat(api.execute(pause).status()).isEqualTo(APPLIED);
    }
    @Test void resolveInvisibleCreateDoesNotClaimItWasNeverCommitted() {
        var c=create();assertThat(api.resolve(c).status()).isEqualTo(UNKNOWN);assertThat(runs()).isZero();
    }
    @Test void concurrentHeartbeatsOnlyIncrementOnce() throws Exception {
        var v=created();var a=command(Action.HEARTBEAT,v,owner);var b=command(Action.HEARTBEAT,v,owner);
        var results=parallel(()->api.execute(a),()->api.execute(b));assertThat(results).extracting(ObservationRunReceipt::status).containsExactlyInAnyOrder(APPLIED,VERSION_CONFLICT);
        assertThat(events()).isEqualTo(1);
    }
    @Test void concurrentTakeoverAndHeartbeatNeverRenewExpiredOwnership() throws Exception {
        var v=created();expire();var expired=api.resolve(command(Action.HEARTBEAT,v,owner)).current();
        var h=command(Action.HEARTBEAT,expired,owner);var t=command(Action.TAKEOVER,expired,UUID.randomUUID());
        var results=parallel(()->api.execute(h),()->api.execute(t));assertThat(results).extracting(ObservationRunReceipt::status).containsExactlyInAnyOrder(LEASE_LOST,APPLIED);
    }
    @Test void uncommittedCreateCommitOrRollbackIsArbitratedByUniqueConstraint() throws Exception {
        for(boolean commit:List.of(true,false)) {
            request=request(false);var command=create();var entered=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
            try(var c=tx()) {
                var tentative=new JdbcObservationRunRepository().execute(c,command);assertThat(tentative.status()).isEqualTo(APPLIED);
                var other=new ObservationRunLifecycle(wrapped((connection,method,args)->{
                    if(method.getName().equals("prepareStatement") && ((String)args[0]).startsWith("insert into payment_safety_classification_runs")) {
                        var statement=(PreparedStatement)invoke(connection,method,args);
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{if(m.getName().equals("executeQuery"))entered.countDown();return invoke(statement,m,a);});
                    }return invoke(connection,method,args);
                }));
                var future=pool.submit(()->other.execute(command));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThat(future.isDone()).isFalse();
                if(commit)c.commit();else c.rollback();
                assertThat(future.get(5,TimeUnit.SECONDS).status()).isEqualTo(commit?REPLAYED:APPLIED);assertThat(events()).isEqualTo(1);
            }finally{pool.shutdownNow();}
        }
    }
    @Test void orderFixturePreventsPauseAndTerminationButIsNeverWrittenByLifecycleApi() {
        var v=created();
        db.update("insert into payment_safety_audit_events(event_uuid,record_digest,event_scope,event_type,run_id,target_order_id,attempt_no,lease_epoch,actor_type,actor_username,occurred_at,payload_format_version,payload) values (?,?,'RUN','ORDER_STARTED',?,1,1,1,'SYSTEM','fixture',clock_timestamp(),1,'{\"version\":1}')",UUID.randomUUID(),"ab".repeat(32),request.requestId());
        assertThat(api.execute(command(Action.PAUSE,v,null)).status()).isEqualTo(UNSUPPORTED_PHASE);
        assertThat(api.execute(command(Action.BEFORE_OBSERVATION_FAILED,v,null)).status()).isEqualTo(UNSUPPORTED_PHASE);
    }
    @Test void existingAssessmentAlsoPreventsPauseAndEarlyFailure() {
        var v=created();
        long user=db.queryForObject("insert into users(username,password,created_at,updated_at) values (?,'unused',localtimestamp,localtimestamp) returning id",Long.class,UUID.randomUUID().toString());
        long order=db.queryForObject("insert into orders(user_id,total_amount,change_deadline_at) values (?,5500,localtimestamp) returning id",Long.class,user);
        var fields=new ArrayList<String>();
        for(var field:com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.OrderRow.class.getRecordComponents()) {
            String tag=switch(field.getType().getSimpleName()) {case "Long"->"int64";case "Integer"->"int32";case "Short"->"int16";case "LocalDateTime"->"timestamp";case "Instant"->"timestamptz";default->"text";};
            fields.add("\""+field.getName()+"\":{\"type\":\""+tag+"\",\"value\":null}");
        }
        String payload="{\"format\":\"payment-safety-input\",\"version\":1,\"facts\":{\"order\":{"+String.join(",",fields)+"},\"payments\":[],\"transactions\":[],\"statusHistory\":[],\"captureRequests\":[],\"verificationProfiles\":[],\"timeOrigins\":[]}}";
        // Isolated fixture only: lifecycle production code has no Assessment INSERT API.
        db.update("insert into payment_safety_assessments(request_id,record_digest,order_id,run_id,attempt_no,phase,input_fingerprint,fingerprint_format_version,classification_contract_version,classifier_impl_version,contract_manifest_digest,disposition,observed_order_total_amount,observed_order_content_revision,capture_transaction_count,capture_request_count,capture_evidence_summary,payload_format_version,input_payload,classification_details,capture_evidence_details) select ?,?,?,id,1,'OBSERVED',?,fingerprint_format_version,classification_contract_version,classifier_impl_version,contract_manifest_digest,'REVIEW',5500,0,0,0,'NOT_APPLICABLE',1,?::jsonb,'{\"version\":1,\"reviewIssues\":[{}]}','{\"version\":1}' from payment_safety_classification_runs where id=?",UUID.randomUUID(),"ab".repeat(32),order,"ab".repeat(32),payload,request.requestId());
        assertThat(api.execute(command(Action.PAUSE,v,null)).status()).isEqualTo(UNSUPPORTED_PHASE);
        assertThat(api.execute(command(Action.BEFORE_OBSERVATION_FAILED,v,null)).status()).isEqualTo(UNSUPPORTED_PHASE);
        assertThat(events()).isEqualTo(1);
    }
    @Test void nonzeroCountsPreventTerminalClaims() {
        var v=created();db.update("update payment_safety_classification_runs set processed_order_count=1,version=version+1 where id=?",request.requestId());
        v=api.resolve(command(Action.HEARTBEAT,v,owner)).current();assertThat(api.execute(command(Action.BEFORE_OBSERVATION_FAILED,v,null)).status()).isEqualTo(UNSUPPORTED_PHASE);
    }
    @Test void observerTimeoutsAreLocalToItsConnection() throws Exception {
        var before=db.queryForMap("select current_setting('lock_timeout') as l,current_setting('statement_timeout') as s,current_setting('transaction_timeout') as t");created();
        assertThat(db.queryForMap("select current_setting('lock_timeout') as l,current_setting('statement_timeout') as s,current_setting('transaction_timeout') as t")).isEqualTo(before);
    }
    @Test void leaseExpiryBetweenCheckAndFinalUpdateRollsBackWithoutEvent() throws Exception {
        var v=created();
        db.update("update payment_safety_classification_runs set lease_until=clock_timestamp()+interval '2 seconds',version=version+1 where id=?",request.requestId());
        v=api.resolve(command(Action.HEARTBEAT,v,owner)).current();
        var pause=command(Action.PAUSE,v,null);
        var atUpdate=new CountDownLatch(1);var release=new CountDownLatch(1);
        var delayed=new ObservationRunLifecycle(wrapped((c,m,args)->{
            if(m.getName().equals("prepareStatement") && ((String)args[0]).startsWith("update payment_safety_classification_runs")) {
                var statement=(PreparedStatement)invoke(c,m,args);
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,method,a)->{
                    if(method.getName().equals("executeQuery")){atUpdate.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new SQLException("test barrier timeout");}
                    return invoke(statement,method,a);
                });
            }return invoke(c,m,args);
        }));
        var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->delayed.execute(pause));assertThat(atUpdate.await(2,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(!db.queryForObject("select lease_until<=clock_timestamp() from payment_safety_classification_runs where id=?",Boolean.class,request.requestId())) {
                if(System.nanoTime()>deadline)throw new AssertionError("lease did not expire");
                Thread.sleep(10);
            }
            release.countDown();assertThat(future.get(5,TimeUnit.SECONDS).status()).isEqualTo(LEASE_LOST);
            assertThat(events()).isEqualTo(1);
            assertThat(db.queryForObject("select status from payment_safety_classification_runs where id=?",String.class,request.requestId())).isEqualTo("RUNNING");
            assertThat(db.queryForObject("select version from payment_safety_classification_runs where id=?",Long.class,request.requestId())).isEqualTo(v.version());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void runLockPrecedesLifecycleEventAccessAndInsert() {
        var v=created();var pause=command(Action.PAUSE,v,null);var sql=new ArrayList<String>();
        var traced=new ObservationRunLifecycle(wrapped((c,m,args)->{
            if(m.getName().equals("prepareStatement"))sql.add((String)args[0]);
            return invoke(c,m,args);
        }));
        assertThat(traced.execute(pause).status()).isEqualTo(APPLIED);
        int lock=-1,eventRead=-1,eventInsert=-1;
        for(int n=0;n<sql.size();n++) {
            if(lock<0 && sql.get(n).contains("for no key update"))lock=n;
            if(eventRead<0 && sql.get(n).startsWith("select * from payment_safety_audit_events"))eventRead=n;
            if(sql.get(n).startsWith("insert into payment_safety_audit_events"))eventInsert=n;
        }
        assertThat(lock).isGreaterThanOrEqualTo(0);assertThat(eventRead).isGreaterThan(lock);assertThat(eventInsert).isGreaterThan(eventRead);
    }
    @Test void heartbeatCommitReplyLossIsOnlyCurrentObservation() {
        var v=created();AtomicBoolean once=new AtomicBoolean();
        var faulty=new ObservationRunLifecycle(wrapped((c,m,args)->{
            if(m.getName().equals("commit") && once.compareAndSet(false,true)){c.commit();throw new SQLException("simulated reply loss","08006");}
            return invoke(c,m,args);
        }));
        var result=faulty.execute(command(Action.HEARTBEAT,v,owner));
        assertThat(result.status()).isEqualTo(OBSERVED);assertThat(result.current().version()).isEqualTo(1);assertThat(events()).isEqualTo(1);
    }
    @Test void lifecyclePreservesAllLegacyColumnsBytesAndExistingAuditRows() {
        long user=db.queryForObject("insert into users(username,password,created_at,updated_at) values (?,'unused',localtimestamp,localtimestamp) returning id",Long.class,UUID.randomUUID().toString());
        long order=db.queryForObject("insert into orders(user_id,total_amount,change_deadline_at) values (?,5500,localtimestamp) returning id",Long.class,user);
        String flow="pfw_"+UUID.randomUUID().toString().replace("-","");String key=UUID.randomUUID().toString();
        long payment=db.queryForObject("insert into payments(order_id,provider,payment_method,status,amount,provider_payment_id) values (?,'PAYJP','CARD','AUTHORIZED',5500,?) returning id",Long.class,order,flow);
        db.update("update orders set active_payment_id=? where id=?",payment,order);
        db.update("insert into order_status_histories(order_id,to_status,changed_by_type,changed_by_username) values (?,'ORDERED','USER','fixture')",order);
        long transaction=db.queryForObject("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,purpose) values (?,'CAPTURE','PENDING',5500,0,?,'SHIPMENT_CAPTURE') returning id",Long.class,payment,key);
        db.update("insert into capture_requests(payment_id,capture_transaction_id,transaction_type,provider,payment_method,provider_flow_id,connection_id,provider_account_ref,mode,api_origin,http_method,api_path,content_type,amount_to_capture,order_content_revision,idempotency_key,body_format_version,request_body,fingerprint_version,request_fingerprint,created_at,replay_not_after) values (?,?,'CAPTURE','PAYJP','CARD',?,'primary','merchant','TEST','https://api.pay.jp','POST',?,'application/json',5500,0,?,1,convert_to('{\"amount_to_capture\":5500}','UTF8'),1,decode(repeat('ab',32),'hex'),current_timestamp,current_timestamp+interval '23 hours')",payment,transaction,flow,"/v2/payment_flows/"+flow+"/capture",key);
        var baselineRequest=request;created();UUID baseline=baselineRequest.requestId();
        var legacy=new LinkedHashMap<String,List<String>>();
        for(String table:List.of("orders","payments","payment_transactions","order_status_histories","capture_requests"))
            legacy.put(table,db.queryForList("select to_jsonb(t)::text from "+table+" t order by id",String.class));
        var runBefore=db.queryForList("select to_jsonb(t)::text from payment_safety_classification_runs t where id=?",String.class,baseline);
        var eventBefore=db.queryForList("select to_jsonb(t)::text from payment_safety_audit_events t where run_id=? order by id",String.class,baseline);
        request=request(true);var v=created();var paused=api.execute(command(Action.PAUSE,v,null));
        var resumed=api.execute(command(Action.RESUME,paused.current(),UUID.randomUUID()));
        assertThat(api.execute(command(Action.EMPTY_SUCCESS,resumed.current(),null)).status()).isEqualTo(APPLIED);
        for(var entry:legacy.entrySet())assertThat(db.queryForList("select to_jsonb(t)::text from "+entry.getKey()+" t order by id",String.class)).isEqualTo(entry.getValue());
        assertThat(db.queryForList("select to_jsonb(t)::text from payment_safety_classification_runs t where id=?",String.class,baseline)).isEqualTo(runBefore);
        assertThat(db.queryForList("select to_jsonb(t)::text from payment_safety_audit_events t where run_id=? order by id",String.class,baseline)).isEqualTo(eventBefore);
        assertThatThrownBy(()->db.update("update capture_requests set connection_id=connection_id where capture_transaction_id=?",transaction)).hasRootCauseInstanceOf(SQLException.class);
    }
    interface ConnectionCall {Object call(Connection c,Method method,Object[] args)throws Throwable;}
    DataSource wrapped(ConnectionCall call) {
        return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{
            Object value=invoke(source,m,a);
            if(m.getName().equals("getConnection")){var c=(Connection)value;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->call.call(c,method,args));}
            return value;
        });
    }
    static Object invoke(Object target,Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    List<ObservationRunReceipt> parallel(Callable<ObservationRunReceipt> a,Callable<ObservationRunReceipt> b)throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var one=pool.submit(()->{start.await();return a.call();});var two=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS));}
        finally {pool.shutdownNow();}
    }
}
