package com.example.ecsite.service.payment.safety.lifecycle;

import com.example.ecsite.service.payment.safety.payload.*;
import com.example.ecsite.service.payment.safety.CaptureEvidenceProfiles;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.payment.safety.lifecycle.ObservationRunReceipt.Status.*;

class ObservationAssessmentPostgresTest {
    static PGSimpleDataSource source;static JdbcTemplate db;
    long order,payment;UUID owner=UUID.randomUUID();ObservationRunRequest request;
    ObservationOrderAttempts api=new ObservationOrderAttempts(source);
    ObservationRunLifecycle lifecycle=new ObservationRunLifecycle(source);
    @BeforeAll static void isolated(){ObservationRunLifecyclePostgresTest.isolated();source=ObservationRunLifecyclePostgresTest.source;db=new JdbcTemplate(source);db.setQueryTimeout(10);}
    void fixture(String disposition) {
        long user=db.queryForObject("insert into users(username,password,created_at,updated_at) values (?,'unused',localtimestamp,localtimestamp) returning id",Long.class,UUID.randomUUID().toString());
        order=db.queryForObject("insert into orders(user_id,total_amount,change_deadline_at) values (?,5500,localtimestamp) returning id",Long.class,user);
        db.update("insert into order_status_histories(order_id,to_status,changed_by_type,changed_by_account_id,changed_by_username) values (?,'ORDERED','USER',?,'user')",order,user);
        if(disposition.equals("NORMAL_NULL")) {
            db.update("update orders set status='CANCELLED',cancelled_at=localtimestamp where id=?",order);
            db.update("insert into order_status_histories(order_id,from_status,to_status,changed_by_type,changed_by_account_id,changed_by_username) values (?,'ORDERED','CANCELLED','USER',?,'user')",order,user);
        }else {
            String flow="pfw_"+UUID.randomUUID().toString().replace("-","");
            payment=db.queryForObject("insert into payments(order_id,provider,payment_method,status,amount,provider_payment_id) values (?,'PAYJP','CARD','AUTHORIZED',5500,?) returning id",Long.class,order,disposition.equals("REVIEW")?null:flow);
            db.update("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,provider_transaction_id,created_at,completed_at,initiator_type,initiator_id,initiator_username) values (?,'AUTHORIZE','SUCCEEDED',5500,0,?,?,timestamp '2026-01-01 08:00:00',timestamp '2026-01-01 08:01:00','USER',?,'user')",payment,UUID.randomUUID().toString(),flow,user);
        }
        request=new ObservationRunRequest(UUID.randomUUID(),ObservationRunRequest.ExecutionType.DIAGNOSTIC,new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.single(order),List.of(order),"v1","impl1",(short)1,CaptureEvidenceProfiles.none(),Map.of(),Map.of(),ObservationRunRequest.Policy.defaults()),ObservationRunRequest.Actor.system("observer"));
        assertThat(lifecycle.execute(lifecycle.prepareCreate(request,owner)).status()).isEqualTo(APPLIED);
    }
    ObservationOrderAttempts.Prepared started(){var s=api.prepareStart(request,order,owner,1,0,request.requester());assertThat(api.execute(s).status()).isEqualTo(APPLIED);return s;}
    long assessments(){return db.queryForObject("select count(*) from payment_safety_assessments where run_id=?",Long.class,request.requestId());}
    @ParameterizedTest @ValueSource(strings={"SELECTED","NORMAL_NULL","REVIEW"})
    void classificationsAreAtomicImmutableReplayableAndCounted(String disposition) {
        fixture(disposition);var started=started();UUID id=UUID.randomUUID();
        var prepared=api.prepareObserved(started,id,1,request.requester());
        var saved=api.execute(prepared);assertThat(saved.status()).isEqualTo(APPLIED);
        assertThat(saved.ledger().counters().classifiedOrderCount()).isEqualTo(1);assertThat(saved.ledger().counters().reviewOrderCount()).isEqualTo(disposition.equals("REVIEW")?1:0);
        assertThat(saved.current().cursor()).isEqualTo(order);assertThat(assessments()).isEqualTo(1);
        var row=db.queryForMap("select * from payment_safety_assessments where request_id=?",id);
        assertThat(row).containsEntry("phase","OBSERVED").containsEntry("disposition",disposition).containsEntry("financial_resumption","NOT_EVALUATED");
        assertThat(row.get("selected_payment_id")).isEqualTo(disposition.equals("SELECTED")?payment:null);
        assertThat(db.queryForObject("select count(*) from payment_safety_audit_events where run_id=? and event_type='ORDER_FINISHED' and outcome='CLASSIFIED' and assessment_id=? and order_id=? and attempt_no=1",Long.class,request.requestId(),row.get("id"),order)).isEqualTo(1);
        assertThat(api.execute(prepared).status()).isEqualTo(REPLAYED);assertThat(api.resolve(prepared).status()).isEqualTo(REPLAYED);assertThat(assessments()).isEqualTo(1);
        assertThat(db.queryForObject("select active_payment_id from orders where id=?",Long.class,order)).isNull();
    }
    @Test void changedFactsAndReusedUuidAreContentConflict() {
        fixture("SELECTED");var start=started();UUID uuid=UUID.randomUUID();var frozen=api.prepareObserved(start,uuid,1,request.requester());
        db.update("update orders set total_amount=4500,content_revision=1 where id=?",order);
        assertThat(api.execute(frozen).status()).isEqualTo(CONTENT_CONFLICT);assertThat(assessments()).isZero();
        var replacement=api.prepareObserved(start,uuid,1,request.requester());assertThat(api.execute(replacement).status()).isEqualTo(APPLIED);
        assertThat(api.execute(frozen).status()).isEqualTo(CONTENT_CONFLICT);
    }
    @Test void finalCasFailureRollsBackAssessmentAndClassifiedEventTogether() {
        fixture("SELECTED");var prepared=api.prepareObserved(started(),UUID.randomUUID(),1,request.requester());String function="stop_"+UUID.randomUUID().toString().replace("-","");
        db.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.id='"+request.requestId()+"'::uuid then return null; end if; return new; end $$");
        db.execute("create trigger "+function+" before update on payment_safety_classification_runs for each row execute function "+function+"()");
        try {assertThat(api.execute(prepared).status()).isEqualTo(LEASE_LOST);assertThat(assessments()).isZero();assertThat(db.queryForObject("select count(*) from payment_safety_audit_events where run_id=? and event_type='ORDER_FINISHED'",Long.class,request.requestId())).isZero();}
        finally{db.execute("drop trigger "+function+" on payment_safety_classification_runs");db.execute("drop function "+function+"()");}
        assertThat(api.execute(prepared).status()).isEqualTo(APPLIED);
    }
    @Test void staleVersionCannotPersistAssessment() {
        fixture("SELECTED");var prepared=api.prepareObserved(started(),UUID.randomUUID(),1,request.requester());
        var heartbeat=lifecycle.prepare(request,ObservationLifecycleCommands.Action.HEARTBEAT,UUID.randomUUID(),owner,owner,1,1,request.requester());
        assertThat(lifecycle.execute(heartbeat).status()).isEqualTo(APPLIED);
        assertThat(api.execute(prepared).status()).isEqualTo(VERSION_CONFLICT);assertThat(assessments()).isZero();
        db.update("update payment_safety_classification_runs set lease_until=clock_timestamp()-interval '1 second',version=version+1 where id=?",request.requestId());
        var takeover=lifecycle.prepare(request,ObservationLifecycleCommands.Action.TAKEOVER,UUID.randomUUID(),owner,UUID.randomUUID(),1,3,request.requester());
        assertThat(lifecycle.execute(takeover).status()).isEqualTo(APPLIED);
        assertThat(api.execute(prepared).status()).isEqualTo(LEASE_LOST);assertThat(assessments()).isZero();
    }
    @Test void eventInsertFailureRollsBackAssessment() {
        fixture("SELECTED");var prepared=api.prepareObserved(started(),UUID.randomUUID(),1,request.requester());String name="reject_"+UUID.randomUUID().toString().replace("-","");
        db.execute("create function "+name+"() returns trigger language plpgsql as $$ begin if new.run_id='"+request.requestId()+"'::uuid and new.event_type='ORDER_FINISHED' then raise exception 'injected'; end if; return new; end $$");
        db.execute("create trigger "+name+" before insert on payment_safety_audit_events for each row execute function "+name+"()");
        try{assertThat(api.execute(prepared).status()).isEqualTo(STORAGE_FAILED);assertThat(assessments()).isZero();}
        finally{db.execute("drop trigger "+name+" on payment_safety_audit_events");db.execute("drop function "+name+"()");}
        assertThat(api.execute(prepared).status()).isEqualTo(APPLIED);
    }
    @Test void lostCommitReplyAndUnavailableReconciliationStayUnknownUntilPairVerified() {
        fixture("SELECTED");var prepared=api.prepareObserved(started(),UUID.randomUUID(),1,request.requester());
        AtomicBoolean once=new AtomicBoolean(),unavailable=new AtomicBoolean();
        var faulty=new ObservationOrderAttempts(wrapped((c,m,a)->{
            if(unavailable.get() && m.getName().equals("prepareStatement"))throw new SQLException("simulated outage","08006");
            if(m.getName().equals("commit") && once.compareAndSet(false,true)){c.commit();unavailable.set(true);throw new SQLException("simulated reply loss","08006");}
            return invoke(c,m,a);
        }));
        assertThat(faulty.execute(prepared).status()).isEqualTo(UNKNOWN);assertThat(assessments()).isEqualTo(1);
        assertThatThrownBy(()->faulty.prepareStart(request,order,owner,1,2,request.requester())).isInstanceOfSatisfying(ObservationOrderAttempts.Rejected.class,e->assertThat(e.status()).isEqualTo(UNKNOWN));
        unavailable.set(false);assertThat(faulty.resolve(prepared).status()).isEqualTo(REPLAYED);
        db.update("update orders set total_amount=4500,content_revision=1 where id=?",order);
        assertThat(faulty.execute(prepared).status()).isEqualTo(REPLAYED);assertThat(assessments()).isEqualTo(1);
    }

    @Test void reducedPendingCaptureEvidenceAndV54BytesAreRetainedWithoutFinancialPermission() {
        fixture("SELECTED");
        db.update("update payments set amount=7000 where id=?",payment);
        db.update("update payment_transactions set amount=7000 where payment_id=?",payment);
        String key=UUID.randomUUID().toString();String flow=db.queryForObject("select provider_payment_id from payments where id=?",String.class,payment);
        long capture=db.queryForObject("insert into payment_transactions(payment_id,transaction_type,status,amount,order_content_revision,idempotency_key,created_at,initiator_type,initiator_id,initiator_username) values (?,'CAPTURE','PENDING',5500,1,?,timestamp '2026-01-01 09:00:00.123456','ADMIN',42,'admin') returning id",Long.class,payment,key);
        db.update("update orders set status='SHIPPED',content_revision=1,shipped_at=timestamp '2026-01-01 09:00:00.123456',shipping_preparation_started_at=timestamp '2026-01-01 08:59:00',shipping_preparation_started_by_account_id=42,shipping_preparation_started_by_username='admin',shipped_by_account_id=42,shipped_by_username='admin',shipment_capture_payment_id=?,active_payment_id=? where id=?",payment,payment,order);
        db.update("insert into order_status_histories(order_id,from_status,to_status,changed_by_type,changed_by_account_id,changed_by_username) values (?,'ORDERED','SHIPPED','ADMIN',42,'admin')",order);
        var identity=new com.example.ecsite.payment.capture.CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp");
        var http=com.example.ecsite.payment.capture.CaptureHttpRequest.create(identity,flow,5500,key);
        db.update("insert into capture_requests(payment_id,capture_transaction_id,transaction_type,provider,payment_method,provider_flow_id,connection_id,provider_account_ref,mode,api_origin,http_method,api_path,content_type,amount_to_capture,order_content_revision,idempotency_key,body_format_version,request_body,fingerprint_version,request_fingerprint,created_at,replay_not_after) values (?,?,'CAPTURE','PAYJP','CARD',?,'primary','merchant','TEST','https://api.pay.jp','POST',?,'application/json',5500,1,?,1,?,1,?,timestamptz '2026-01-01 00:00:00.123456+00',timestamptz '2026-01-01 23:00:00.123456+00')",payment,capture,flow,http.path(),key,http.body(),http.fingerprint());
        String before=db.queryForObject("select to_jsonb(r)::text from capture_requests r where capture_transaction_id=?",String.class,capture);
        var profiles=new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("approved","reference",Map.of(capture,identity)),new CaptureEvidenceProfiles.TimestampBindingProfile("v1","reference",Map.of(capture,"Asia/Tokyo")));
        request=new ObservationRunRequest(UUID.randomUUID(),ObservationRunRequest.ExecutionType.DIAGNOSTIC,new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.single(order),List.of(order),"v1","impl1",(short)1,profiles,Map.of(),Map.of(),ObservationRunRequest.Policy.defaults()),request.requester());
        assertThat(lifecycle.execute(lifecycle.prepareCreate(request,owner)).status()).isEqualTo(APPLIED);
        UUID id=UUID.randomUUID();var prepared=api.prepareObserved(started(),id,1,request.requester());
        assertThat(api.execute(prepared).status()).isEqualTo(APPLIED);
        assertThat(db.queryForMap("select disposition,capture_evidence_summary,financial_resumption from payment_safety_assessments where request_id=?",id)).containsEntry("disposition","SELECTED").containsEntry("capture_evidence_summary","VERIFIED").containsEntry("financial_resumption","NOT_EVALUATED");
        assertThat(db.queryForObject("select to_jsonb(r)::text from capture_requests r where capture_transaction_id=?",String.class,capture)).isEqualTo(before);
        assertThat(db.queryForObject("select status from payment_transactions where id=?",String.class,capture)).isEqualTo("PENDING");
        assertThat(api.resolve(prepared).status()).isEqualTo(REPLAYED);
    }

    @Test void concurrentCommitBetweenPreflightAndFailedSnapshotReadStillReplays() {
        fixture("SELECTED");var prepared=api.prepareObserved(started(),UUID.randomUUID(),1,request.requester());
        AtomicBoolean once=new AtomicBoolean();
        var concurrent=new ObservationOrderAttempts(wrapped((c,m,a)->{
            if(m.getName().equals("prepareStatement") && a[0].toString().contains("current_setting('lock_timeout')") && once.compareAndSet(false,true)) {
                assertThat(api.execute(prepared).status()).isEqualTo(APPLIED);
                throw new SQLNonTransientException("simulated read limit after concurrent commit","54000");
            }
            return invoke(c,m,a);
        }));
        assertThat(concurrent.execute(prepared).status()).isEqualTo(REPLAYED);assertThat(assessments()).isEqualTo(1);
    }

    @Test void assessmentWithoutFinishedIsIntegrityErrorAndNeverAutomaticallyCompleted() throws Exception {
        fixture("SELECTED");var prepared=api.prepareObserved(started(),UUID.randomUUID(),1,request.requester());
        try(var c=source.getConnection()){
            ObservationRunLifecycle.configure(c,request.manifest().policy());var store=new JdbcObservationAssessmentStore();
            var record=store.observe(c,request,order,1);store.insert(c,store.prepare(c,UUID.randomUUID(),record));c.commit();
        }
        assertThat(api.resolve(prepared).status()).isEqualTo(INTEGRITY_ERROR);
        assertThat(api.execute(prepared).status()).isEqualTo(INTEGRITY_ERROR);assertThat(assessments()).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from payment_safety_audit_events where run_id=? and event_type='ORDER_FINISHED'",Long.class,request.requestId())).isZero();
    }
    @Test void nextOrderAfterClassifiedReusesVerifiedReferencesAndRecomputesAllCounters() {
        fixture("SELECTED");long first=order;
        fixture("NORMAL_NULL");long second=order;
        request=new ObservationRunRequest(UUID.randomUUID(),ObservationRunRequest.ExecutionType.DIAGNOSTIC,new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.range(first-1,second),List.of(first,second),"v1","impl1",(short)1,CaptureEvidenceProfiles.none(),Map.of(),Map.of(),ObservationRunRequest.Policy.defaults()),request.requester());
        assertThat(lifecycle.execute(lifecycle.prepareCreate(request,owner)).status()).isEqualTo(APPLIED);
        var one=api.prepareStart(request,first,owner,1,0,request.requester());assertThat(api.execute(one).status()).isEqualTo(APPLIED);
        assertThat(api.execute(api.prepareObserved(one,UUID.randomUUID(),1,request.requester())).status()).isEqualTo(APPLIED);
        var two=api.prepareStart(request,second,owner,1,2,request.requester());assertThat(api.execute(two).status()).isEqualTo(APPLIED);
        var saved=api.execute(api.prepareObserved(two,UUID.randomUUID(),3,request.requester()));
        assertThat(saved.status()).isEqualTo(APPLIED);assertThat(assessments()).isEqualTo(2);
        assertThat(JdbcObservationOrderAttemptRepository.counts(saved.ledger().counters())).containsExactly(2L,2L,0L,0L,0L,0L,0L,0L,0L,0L,0L);
        assertThat(saved.current().cursor()).isEqualTo(second);
    }

    @Test void actualSaveLocksOrderThenPaymentsThenRunAndCasIsLastSql() {
        fixture("SELECTED");var prepared=api.prepareObserved(started(),UUID.randomUUID(),1,request.requester());
        List<String> sql=new ArrayList<>();var traced=new ObservationOrderAttempts(wrapped((c,m,a)->{
            if(m.getName().equals("prepareStatement"))sql.add(a[0].toString());return invoke(c,m,a);
        }));
        assertThat(traced.execute(prepared).status()).isEqualTo(APPLIED);
        int orderLock=-1,paymentLock=-1,runLock=-1;
        for(int i=0;i<sql.size();i++) {
            String q=sql.get(i).toLowerCase(Locale.ROOT);
            if(q.contains("from orders o") && q.endsWith("for update"))orderLock=i;
            if(q.contains("from payments p") && q.endsWith("for update")){paymentLock=i;assertThat(q).contains("order by p.id");}
            if(orderLock>=0 && q.contains("for no key update"))runLock=i;
        }
        assertThat(orderLock).isGreaterThanOrEqualTo(0);assertThat(paymentLock).isGreaterThan(orderLock);assertThat(runLock).isGreaterThan(paymentLock);
        assertThat(sql.getLast()).startsWith("update payment_safety_classification_runs");
    }
    @FunctionalInterface interface ConnectionCall{Object call(Connection c,Method m,Object[] a)throws Throwable;}
    DataSource wrapped(ConnectionCall call){return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{
        Object value=invoke(source,m,a);if(m.getName().equals("getConnection")){var c=(Connection)value;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->call.call(c,method,args));}return value;
    });}
    static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}

}
