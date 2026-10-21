package com.example.ecsite.service.payment.safety.lifecycle;

import com.example.ecsite.service.payment.safety.payload.*;
import com.example.ecsite.service.payment.safety.payload.ObservationOrderContextV1.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.payment.safety.lifecycle.ObservationRunReceipt.Status.*;

class ObservationOrderAttemptsPostgresTest {
    static PGSimpleDataSource source;static JdbcTemplate db;
    ObservationRunRequest request=ObservationRunLifecyclePostgresTest.request(false);
    UUID owner=UUID.randomUUID();
    ObservationRunLifecycle lifecycle=new ObservationRunLifecycle(source);
    ObservationOrderAttempts api=new ObservationOrderAttempts(source);
    @BeforeAll static void isolated() {
        // Existing helper checks IsolatedDatabaseGuard BEFORE DataSource/Flyway/JDBC access.
        ObservationRunLifecyclePostgresTest.isolated();source=ObservationRunLifecyclePostgresTest.source;db=new JdbcTemplate(source);db.setQueryTimeout(10);
    }
    @BeforeEach void create(){assertThat(lifecycle.execute(lifecycle.prepareCreate(request,owner)).status()).isEqualTo(APPLIED);}
    ObservationOrderAttempts.Prepared start(long target,long version){return api.prepareStart(request,target,owner,1,version,request.requester());}
    ObservationOrderAttempts.Prepared fail(ObservationOrderAttempts.Prepared s,long version,Failure failure)throws Exception {
        try(var c=source.getConnection()){c.setAutoCommit(false);return api.prepareFailureAfterRollback(c,s,version,failure,request.requester());}
    }
    long count(){return db.queryForObject("select count(*) from payment_safety_audit_events where run_id=? and event_type in ('ORDER_STARTED','ORDER_FINISHED')",Long.class,request.requestId());}
    @Test void startedFailureThreeAttemptsCountersAndCursorAreAtomicAndReplayDoesNotAdd() throws Exception {
        long version=0;
        for(int attempt=1;attempt<=3;attempt++) {
            var s=start(1,version);assertThat(s.event().record().attemptNo()).isEqualTo(attempt);
            var r=api.execute(s);assertThat(r.status()).isEqualTo(APPLIED);assertThat(r.ledger().openTrial()).isPresent();version++;
            assertThat(api.execute(s).status()).isEqualTo(REPLAYED);
            var failure=switch(attempt){case 1->new Failure(Stage.ACQUISITION,Code.READ_FAILED);case 2->new Failure(Stage.ASSEMBLY,Code.UNREPRESENTABLE_INPUT);default->new Failure(Stage.PERSISTENCE,Code.STORAGE_FAILED);};
            var f=fail(s,version,failure);r=api.execute(f);assertThat(r.status()).isEqualTo(APPLIED);version++;
            assertThat(api.execute(f).status()).isEqualTo(REPLAYED);assertThat(r.current().cursor()).isEqualTo(1);
            assertThat(r.ledger().counters()).isEqualTo(new ObservationLedgerCounters(1,0,0,1,attempt>=2?1:0,attempt==3?1:0,0,attempt-1,1,0,0));
        }
        long expected=version;assertThatThrownBy(()->start(1,expected)).isInstanceOf(ObservationOrderAttempts.Rejected.class);
        assertThat(count()).isEqualTo(6);
    }
    @Test void concurrentStartsOnlyOneIsSavedAndDifferentContentAtSameSlotConflicts() throws Exception {
        var first=start(1,0);var other=new ObservationOrderAttempts(source);var second=other.prepareStart(request,10,owner,1,0,request.requester());
        var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{go.await();return api.execute(first);});var b=pool.submit(()->{go.await();return other.execute(second);});go.countDown();
            var statuses=List.of(a.get(5,TimeUnit.SECONDS).status(),b.get(5,TimeUnit.SECONDS).status());assertThat(statuses).contains(APPLIED);assertThat(Collections.frequency(statuses,APPLIED)).isEqualTo(1);assertThat(count()).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void conflictingEventContentIsNotReplayed() {
        var a=start(1,0);var b=start(1,0);assertThat(api.execute(a).status()).isEqualTo(APPLIED);
        assertThat(api.execute(b).status()).isEqualTo(CONTENT_CONFLICT);assertThat(count()).isEqualTo(1);
    }
    @Test void oldWorkerCannotFinishAndNewOwnerCanInterruptAfterCommittedTakeover() throws Exception {
        var s=start(1,0);assertThat(api.execute(s).status()).isEqualTo(APPLIED);
        var oldFailure=fail(s,1,new Failure(Stage.ASSEMBLY,Code.UNREPRESENTABLE_INPUT));
        db.update("update payment_safety_classification_runs set lease_until=clock_timestamp()-interval '1 second',version=version+1 where id=?",request.requestId());
        UUID next=UUID.randomUUID();var takeover=lifecycle.prepare(request,ObservationLifecycleCommands.Action.TAKEOVER,UUID.randomUUID(),owner,next,1,2,request.requester());
        var taken=lifecycle.execute(takeover);assertThat(taken.status()).isEqualTo(APPLIED);assertThat(api.execute(oldFailure).status()).isEqualTo(LEASE_LOST);
        var interrupted=api.prepareInterrupted(request,takeover,3,request.requester());var r=api.execute(interrupted);assertThat(r.status()).isEqualTo(APPLIED);assertThat(r.ledger().counters().interruptedAttemptCount()).isEqualTo(1);
        var retry=api.prepareStart(request,1,next,2,4,request.requester());assertThat(retry.event().record().attemptNo()).isEqualTo(2);assertThat(api.execute(retry).status()).isEqualTo(APPLIED);
        var replay=api.execute(s);assertThat(replay.status()).isEqualTo(REPLAYED);assertThat(replay.current().owner()).isEqualTo(next); // historical receipt grants no old lease
    }
    @Test void rollbackMustSucceedBeforeFailureEnvelopeCanExist() throws Exception {
        var s=start(1,0);assertThat(api.execute(s).status()).isEqualTo(APPLIED);
        try(var c=source.getConnection()) {
            assertThatThrownBy(()->api.prepareFailureAfterRollback(c,s,1,new Failure(Stage.PERSISTENCE,Code.STORAGE_FAILED),request.requester())).isInstanceOf(ObservationOrderAttempts.Rejected.class);
            c.setAutoCommit(false);try(var q=c.createStatement()){q.execute("create table rollback_marker(id integer)");}
            var f=api.prepareFailureAfterRollback(c,s,1,new Failure(Stage.PERSISTENCE,Code.STORAGE_FAILED),request.requester());
            assertThat(db.queryForObject("select to_regclass('rollback_marker') is null",Boolean.class)).isTrue();assertThat(api.execute(f).status()).isEqualTo(APPLIED);
        }
    }
    @Test void corruptedSavedCountersOrCursorAreRejectedWithoutRepair() {
        var s=start(1,0);assertThat(api.execute(s).status()).isEqualTo(APPLIED);
        db.update("update payment_safety_classification_runs set processed_order_count=1,version=version+1 where id=?",request.requestId());
        assertThat(api.resolve(s).status()).isEqualTo(INTEGRITY_ERROR);assertThat(count()).isEqualTo(1);
        db.update("update payment_safety_classification_runs set processed_order_count=0,cursor_order_id=10,version=version+1 where id=?",request.requestId());
        assertThat(api.resolve(s).status()).isEqualTo(INTEGRITY_ERROR);assertThat(db.queryForObject("select cursor_order_id from payment_safety_classification_runs where id=?",Long.class,request.requestId())).isEqualTo(10);
    }
    @Test void commitReplyLossUsesNewConnectionAndReplaysOriginalEvent() {
        AtomicBoolean once=new AtomicBoolean();var faulty=new ObservationOrderAttempts(wrapped((c,m,a)->{
            if(m.getName().equals("commit") && once.compareAndSet(false,true)){c.commit();throw new SQLException("simulated reply loss","08006");}return invoke(c,m,a);
        }));
        var s=faulty.prepareStart(request,1,owner,1,0,request.requester());assertThat(faulty.execute(s).status()).isEqualTo(REPLAYED);assertThat(count()).isEqualTo(1);
    }
    @Test void finalCasRejectionRollsBackEverythingAndUnacknowledgedRollbackStaysUnknown() throws Exception {
        var s=start(1,0);String function="suppress_"+UUID.randomUUID().toString().replace("-","");
        db.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.id='"+request.requestId()+"'::uuid then return null; end if; return new; end $$");
        db.execute("create trigger "+function+" before update on payment_safety_classification_runs for each row execute function "+function+"()");
        AtomicBoolean loseRollback=new AtomicBoolean(),failResolve=new AtomicBoolean();
        var faulty=new ObservationOrderAttempts(wrapped((c,m,a)->{
            if(failResolve.get() && m.getName().equals("prepareStatement"))throw new SQLException("simulated resolver outage","08006");
            if(loseRollback.get() && m.getName().equals("rollback")){failResolve.set(true);throw new SQLException("simulated rollback reply loss","08006");}
            return invoke(c,m,a);
        }));
        try {
            assertThat(api.execute(s).status()).isEqualTo(LEASE_LOST);assertThat(count()).isZero();
            loseRollback.set(true);assertThat(faulty.execute(s).status()).isEqualTo(UNKNOWN);assertThat(count()).isZero();
            assertThatThrownBy(()->faulty.prepareStart(request,10,owner,1,0,request.requester())).isInstanceOfSatisfying(ObservationOrderAttempts.Rejected.class,e->assertThat(e.status()).isEqualTo(UNKNOWN));
            loseRollback.set(false);failResolve.set(false);assertThat(faulty.resolve(s).status()).isEqualTo(NOT_APPLIED);
            assertThat(db.queryForObject("select version from payment_safety_classification_runs where id=?",Long.class,request.requestId())).isZero();
        }finally{db.execute("drop trigger "+function+" on payment_safety_classification_runs");db.execute("drop function "+function+"()");}
        assertThat(faulty.prepareStart(request,1,owner,1,0,request.requester()).event().record().attemptNo()).isEqualTo(1);
    }
    @Test void unknownCommittedFinishBlocksNextAttemptUntilExplicitReplayResolution() throws Exception {
        var s=start(1,0);assertThat(api.execute(s).status()).isEqualTo(APPLIED);
        AtomicBoolean loseCommit=new AtomicBoolean(),blocked=new AtomicBoolean();
        var faulty=new ObservationOrderAttempts(wrapped((c,m,a)->{
            if(blocked.get() && m.getName().equals("prepareStatement"))throw new SQLException("simulated resolver outage","08006");
            if(m.getName().equals("commit") && loseCommit.compareAndSet(false,true)){c.commit();blocked.set(true);throw new SQLException("simulated commit reply loss","08006");}
            return invoke(c,m,a);
        }));
        ObservationOrderAttempts.Prepared f;
        try(var c=source.getConnection()){c.setAutoCommit(false);f=faulty.prepareFailureAfterRollback(c,s,1,new Failure(Stage.ACQUISITION,Code.READ_FAILED),request.requester());}
        assertThat(faulty.execute(f).status()).isEqualTo(UNKNOWN);
        assertThatThrownBy(()->faulty.prepareStart(request,1,owner,1,2,request.requester())).isInstanceOfSatisfying(ObservationOrderAttempts.Rejected.class,e->assertThat(e.status()).isEqualTo(UNKNOWN));
        blocked.set(false);assertThat(faulty.resolve(f).status()).isEqualTo(REPLAYED);
        assertThat(faulty.prepareStart(request,1,owner,1,2,request.requester()).event().record().attemptNo()).isEqualTo(2);assertThat(count()).isEqualTo(2);
    }
    @Test void resolverLockTimeoutPreservesUnknownGate() throws Exception {
        var s=start(1,0);
        try(var c=source.getConnection()){c.setAutoCommit(false);try(var q=c.prepareStatement("select id from payment_safety_classification_runs where id=? for no key update")){q.setObject(1,request.requestId());q.executeQuery().close();}
            assertThat(api.resolve(s).status()).isEqualTo(UNKNOWN);
            assertThatThrownBy(()->start(10,0)).isInstanceOfSatisfying(ObservationOrderAttempts.Rejected.class,e->assertThat(e.status()).isEqualTo(UNKNOWN));c.rollback();}
        assertThat(api.resolve(s).status()).isEqualTo(NOT_APPLIED);assertThat(api.execute(s).status()).isEqualTo(APPLIED);
    }
    @Test void failureEnvelopeIsNotPreparedWhenOriginalRollbackFails() throws Exception {
        var s=start(1,0);assertThat(api.execute(s).status()).isEqualTo(APPLIED);
        var broken=wrapped((c,m,a)->{if(m.getName().equals("rollback"))throw new SQLException("rollback failed","08006");return invoke(c,m,a);});
        try(var c=broken.getConnection()){c.setAutoCommit(false);assertThatThrownBy(()->api.prepareFailureAfterRollback(c,s,1,new Failure(Stage.ACQUISITION,Code.READ_FAILED),request.requester())).isInstanceOfSatisfying(ObservationOrderAttempts.Rejected.class,e->assertThat(e.status()).isEqualTo(UNKNOWN));}
        assertThat(count()).isEqualTo(1);
    }
    @Test void missingTakeoverEventCannotAuthorizeInterruption() {
        var s=start(1,0);assertThat(api.execute(s).status()).isEqualTo(APPLIED);
        UUID next=UUID.randomUUID();
        db.update("update payment_safety_classification_runs set lease_until=clock_timestamp()-interval '1 second',version=version+1 where id=?",request.requestId());
        var uncommitted=lifecycle.prepare(request,ObservationLifecycleCommands.Action.TAKEOVER,UUID.randomUUID(),owner,next,1,2,request.requester());
        // Isolated invalid-history fixture: valid current lease but no matching persisted takeover Event.
        db.update("update payment_safety_classification_runs set lease_owner=?,lease_epoch=2,lease_until=clock_timestamp()+interval '120 seconds',version=version+1 where id=?",next,request.requestId());
        assertThatThrownBy(()->api.prepareInterrupted(request,uncommitted,3,request.requester())).isInstanceOfSatisfying(ObservationOrderAttempts.Rejected.class,e->assertThat(e.status()).isEqualTo(UNSUPPORTED_PHASE));assertThat(count()).isEqualTo(1);
    }
    @Test void corruptStoredEventDigestIsIntegrityErrorAndIsNeverRepaired() {
        var prepared=start(1,0);var r=prepared.event().record();
        db.update("insert into payment_safety_audit_events(event_uuid,record_digest,event_scope,event_type,run_id,target_order_id,attempt_no,lease_epoch,actor_type,actor_username,occurred_at,payload_format_version,payload) values (?,?,'RUN','ORDER_STARTED',?,1,1,1,'SYSTEM',?,?,1,?::jsonb)",prepared.event().eventUuid(),"ab".repeat(32),request.requestId(),r.actorUsername(),r.occurredAt().atOffset(java.time.ZoneOffset.UTC),r.payloadJson());
        assertThat(api.execute(prepared).status()).isEqualTo(INTEGRITY_ERROR);assertThat(count()).isEqualTo(1);
        assertThat(db.queryForObject("select record_digest from payment_safety_audit_events where event_uuid=?",String.class,prepared.event().eventUuid())).isEqualTo("ab".repeat(32));
        assertThat(db.queryForObject("select version from payment_safety_classification_runs where id=?",Long.class,request.requestId())).isZero();
    }
    @Test void finalCasIsLastStatementBeforeCommit() {
        var prepared=start(1,0);AtomicBoolean afterCas=new AtomicBoolean();AtomicInteger statementsAfterCas=new AtomicInteger();
        var traced=new ObservationOrderAttempts(wrapped((c,m,a)->{
            if(m.getName().equals("prepareStatement")) {
                if(afterCas.get())statementsAfterCas.incrementAndGet();
                if(((String)a[0]).startsWith("update payment_safety_classification_runs"))afterCas.set(true);
            }
            if(m.getName().equals("commit"))afterCas.set(false);
            return invoke(c,m,a);
        }));
        assertThat(traced.execute(prepared).status()).isEqualTo(APPLIED);assertThat(statementsAfterCas.get()).isZero();
    }
    interface ConnectionCall{Object call(Connection c,Method m,Object[] a)throws Throwable;}
    DataSource wrapped(ConnectionCall call) {
        return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{
            Object value=invoke(source,m,a);
            if(m.getName().equals("getConnection")){var c=(Connection)value;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->call.call(c,method,args));}return value;
        });
    }
    static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
}
