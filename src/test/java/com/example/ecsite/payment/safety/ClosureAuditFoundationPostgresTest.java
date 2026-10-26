package com.example.ecsite.payment.safety;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.ecsite.repository.orderchange.NewFlowClosureAuditRepository;
import com.example.ecsite.repository.orderchange.NewFlowClosureAuditRepository.*;

/** Fresh launcher-owned PG17 only; no Spring application, Gateway or SMTP is instantiated. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClosureAuditFoundationPostgresTest {
    OrderChangeFinancialRequestsPostgresTest fixture;
    JdbcTemplate jdbc;
    NewFlowClosureAuditRepository repository;
    TransactionTemplate transaction;
    final Instant now=Instant.parse("2026-10-23T01:00:00Z");
    Map<String,List<String>> financialBefore;
    long unrelatedPayment,otherOrder,otherPayment;
    @BeforeAll void setup(){
        PaymentSafetyExpandPostgresTest.verifyContainerBeforeAnyDatabaseAccess();
        fixture=new OrderChangeFinancialRequestsPostgresTest();fixture.initialize(true);fixture.save();fixture.db.migrate("65");jdbc=fixture.jdbc;
        unrelatedPayment=fixture.db.payment(fixture.order);otherOrder=fixture.db.order();otherPayment=fixture.db.payment(otherOrder);
        financialBefore=snapshot();fixture.db.migrate("66");assertThat(snapshot()).isEqualTo(financialBefore);
        repository=new NewFlowClosureAuditRepository(jdbc);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(fixture.db.source));
    }
    <T>T write(Supplier<T> body){return transaction.execute(s->body.get());}
    Map<String,List<String>> snapshot(){
        var result=new TreeMap<String,List<String>>();
        for(var table:List.of("orders","payments","payment_transactions","order_change_operations","inventory_reservations","order_change_financial_requests","order_change_request_dispatches","order_change_closure_scan_cursor","products"))
            result.put(table,jdbc.queryForList("select to_jsonb(t)::text from "+table+" t order by to_jsonb(t)::text",String.class));
        return result;
    }
    Issue issue(){return new Issue(null,"OPERATION","OP:"+fixture.operation,fixture.operation,fixture.order,fixture.payment,"TEST_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT),"HIGH","OPEN",1,1,now,now,null,null,null,null,0,null,null,now,now);}
    Attempt attempt(){return new Attempt(UUID.randomUUID(),fixture.operation,fixture.order,fixture.payment,null,"RECOVERY",new byte[32],"STARTED",null,null,0,now,null,now);}
    Run run(){return new Run(UUID.randomUUID(),"RECOVERY","RUNNING",0,now,null,now,null,0,0,0,0,0,true,null,now);}
    Event event(long issue,UUID attempt,UUID command){return new Event(UUID.randomUUID(),issue,1,"DETECTED",attempt,command,"SYSTEM",null,now,"TEST_REASON",new byte[32],null);}
    void rejects(Runnable body){assertThatThrownBy(body::run).isInstanceOf(DataAccessException.class);}

    @Test void migrationPreservesRowsAndCreatesValidatedStructure(){
        assertThat(snapshot()).isEqualTo(financialBefore);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=? and table_name in ('order_change_closure_issues','order_change_closure_issue_events','order_change_closure_attempts','order_change_closure_runs')",Integer.class,fixture.db.schema)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select bool_and(convalidated) from pg_constraint where conrelid in ('order_change_closure_issues'::regclass,'order_change_closure_attempts'::regclass,'order_change_closure_runs'::regclass,'order_change_closure_issue_events'::regclass)",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select bool_and(indisvalid and indisready) from pg_index where indrelid in ('order_change_closure_issues'::regclass,'order_change_closure_attempts'::regclass,'order_change_closure_runs'::regclass,'order_change_closure_issue_events'::regclass)",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where not tgisinternal and tgrelid in ('order_change_closure_issues'::regclass,'order_change_closure_attempts'::regclass,'order_change_closure_runs'::regclass,'order_change_closure_issue_events'::regclass) and tgenabled='O'",Integer.class)).isEqualTo(9);
    }
    @Test void repositoryRoundTripsAllModelsAndSearchesWithoutFinancialWrites(){
        var a=write(()->repository.insertAttempt(attempt()));var i=write(()->repository.insertIssue(issue()));
        var e=write(()->repository.appendEvent(event(i.id(),a.id(),UUID.randomUUID())));var r=write(()->repository.insertRun(run()));
        assertThat(repository.findIssue(i.id())).contains(i);assertThat(repository.findIssue(i.subjectType(),i.subjectKey(),i.anomalyType())).contains(i);
        assertThat(repository.openIssues(1000)).contains(i);assertThat(repository.issuesForOperation(fixture.operation)).contains(i);
        assertThat(repository.eventsForIssue(i.id()).getFirst().id()).isEqualTo(e.id());
        assertThat(repository.findAttempt(a.id()).orElseThrow().id()).isEqualTo(a.id());assertThat(repository.attemptsForOperation(fixture.operation)).extracting(Attempt::id).contains(a.id());
        assertThat(repository.findRun(r.id())).contains(r);assertThat(repository.recentRuns("RECOVERY",1000)).contains(r);
        assertThat(repository.unfinishedAttempts(now.plusSeconds(1),1000)).extracting(Attempt::id).contains(a.id());
        assertThat(repository.unfinishedRuns(now.plusSeconds(1),1000)).contains(r);
        // A new repository has no in-memory recovery state; unfinished rows survive its lifetime.
        var restarted=new NewFlowClosureAuditRepository(jdbc);
        assertThat(restarted.unfinishedRuns(now.plusSeconds(1),1000)).contains(r);
        assertThat(restarted.unfinishedAttempts(now.plusSeconds(1),1000)).extracting(Attempt::id).contains(a.id());
        assertThat(snapshot()).isEqualTo(financialBefore);
    }
    @Test void writesRequireCallerOwnedWritableTransactionAndDigestsAreDefensive(){
        assertThatThrownBy(()->repository.insertRun(run())).isInstanceOf(IllegalStateException.class);
        var readOnly=new TransactionTemplate(new DataSourceTransactionManager(fixture.db.source));readOnly.setReadOnly(true);
        assertThatThrownBy(()->readOnly.execute(s->repository.insertRun(run()))).isInstanceOf(IllegalStateException.class);
        var bytes=new byte[32];var a=new Attempt(UUID.randomUUID(),fixture.operation,fixture.order,fixture.payment,null,"RECOVERY",bytes,"STARTED",null,null,0,now,null,now);
        bytes[0]=1;a.contextDigest()[0]=2;assertThat(a.contextDigest()[0]).isZero();assertThat(a.toString()).doesNotContain(fixture.operation.toString());
    }
    @Test void rejectsWrongOrderOldPaymentAndUnrelatedNewPaymentAtDatabaseBoundary(){
        var old=jdbc.queryForObject("select old_payment_id from order_change_operations where id=?",Long.class,fixture.operation);
        for(long payment:List.of(old,unrelatedPayment,otherPayment)){
            rejects(()->jdbc.update("insert into order_change_closure_attempts(id,operation_id,order_id,new_payment_id,trigger_kind,context_digest,status,started_at,created_at) values(?,?,?,?,'RECOVERY',decode(repeat('00',32),'hex'),'STARTED',?,?)",UUID.randomUUID(),fixture.operation,fixture.order,payment,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now)));
        }
        rejects(()->jdbc.update("insert into order_change_closure_attempts(id,operation_id,order_id,new_payment_id,trigger_kind,context_digest,status,started_at,created_at) values(?,?,?,?,'RECOVERY',decode(repeat('00',32),'hex'),'STARTED',?,?)",UUID.randomUUID(),fixture.operation,otherOrder,otherPayment,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now)));
    }
    @Test void stableCaseUniquenessSubjectChecksAndVersionGuard(){
        var i=write(()->repository.insertIssue(issue()));rejects(()->write(()->repository.insertIssue(iWithNoId(i))));
        for(String set:List.of("subject_key=null","episode_no=0","detection_count=0","status='BAD'","last_detected_at=first_detected_at-interval '1 second'","acknowledged_by=1","status='RESOLVED'","new_payment_id=null"))
            rejects(()->jdbc.update("update order_change_closure_issues set "+set+",version=version+1 where id=?",i.id()));
        rejects(()->jdbc.update("update order_change_closure_issues set detection_count=2 where id=?",i.id()));
        assertThat(jdbc.update("update order_change_closure_issues set detection_count=2,version=version+1 where id=? and version=0",i.id())).isEqualTo(1);
        assertThat(jdbc.update("update order_change_closure_issues set detection_count=3,version=version+1 where id=? and version=0",i.id())).isZero();
    }
    Issue iWithNoId(Issue i){return new Issue(null,i.subjectType(),i.subjectKey(),i.operationId(),i.orderId(),i.newPaymentId(),i.anomalyType(),i.severity(),i.status(),1,1,now,now,null,null,null,null,0,null,null,now,now);}
    @Test void systemAndSchedulerTargetsNeedNoFictitiousFinancialRows(){
        for(String type:List.of("SYSTEM","SCHEDULER")){
            var i=new Issue(null,type,type+":TEST_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT),null,null,null,"NO_HEARTBEAT","WARNING","OPEN",1,1,now,now,null,null,null,null,0,null,null,now,now);
            var saved=write(()->repository.insertIssue(i));assertThat(saved.operationId()).isNull();assertThat(saved.newPaymentId()).isNull();
            rejects(()->jdbc.update("update order_change_closure_issues set order_id=?,version=version+1 where id=?",fixture.order,saved.id()));
        }
    }
    @Test void appendOnlyEventsRejectMutationEvenWithOwnerPrivileges(){
        var i=write(()->repository.insertIssue(issue()));var e=write(()->repository.appendEvent(event(i.id(),null,UUID.randomUUID())));
        String before=jdbc.queryForObject("select to_jsonb(e)::text from order_change_closure_issue_events e where id=?",String.class,e.id());
        for(String sql:List.of("update order_change_closure_issue_events set event_kind=event_kind","delete from order_change_closure_issue_events","truncate order_change_closure_issue_events"))rejects(()->jdbc.execute(sql));
        assertThat(jdbc.queryForObject("select to_jsonb(e)::text from order_change_closure_issue_events e where id=?",String.class,e.id())).isEqualTo(before);
        for(String set:List.of("episode_no=0","context_digest=decode('00','hex')","actor_type='ADMIN'")){
            // Test INSERT validation without invoking the append-only UPDATE guard.
            rejects(()->jdbc.execute("insert into order_change_closure_issue_events select gen_random_uuid(),issue_id,"+(set.startsWith("episode")?"0":"episode_no")+",event_kind,null,null,"+(set.startsWith("actor")?"'ADMIN'":"actor_type")+",null,occurred_at,safe_reason_code,"+(set.startsWith("context")?"decode('00','hex')":"context_digest")+",comment from order_change_closure_issue_events where id='"+e.id()+"'"));
        }
    }
    @Test void eventCommandAndAttemptIdempotencyAndOwnerChecks(){
        var a=write(()->repository.insertAttempt(attempt()));var i=write(()->repository.insertIssue(issue()));var command=UUID.randomUUID();
        write(()->repository.appendEvent(event(i.id(),a.id(),command)));
        rejects(()->write(()->repository.appendEvent(event(i.id(),null,command))));
        rejects(()->write(()->repository.appendEvent(event(i.id(),a.id(),UUID.randomUUID()))));
        var nullTarget=new Attempt(UUID.randomUUID(),fixture.operation,fixture.order,null,null,"RECOVERY",new byte[32],"STARTED",null,null,0,now,null,now);
        write(()->repository.insertAttempt(nullTarget));
        rejects(()->write(()->repository.appendEvent(event(i.id(),nullTarget.id(),null))));
        rejects(()->jdbc.update("update order_change_closure_issues set last_attempt_id=?,version=version+1 where id=?",nullTarget.id(),i.id()));
    }
    @Test void attemptStatesCasAndTerminalImmutability(){
        for(String state:List.of("COMPLETED","UNKNOWN")){
            var a=write(()->repository.insertAttempt(attempt()));
            rejects(()->write(()->repository.insertAttempt(a)));
            assertThat(write(()->repository.finishAttempt(a.id(),0,state,state.equals("COMPLETED")?"NOT_CLOSED":null,state.equals("UNKNOWN")?"COMMIT_UNKNOWN":null,now.plusSeconds(1)))).isTrue();
            assertThat(write(()->repository.finishAttempt(a.id(),0,state,"NOT_CLOSED",null,now.plusSeconds(2)))).isFalse();
            rejects(()->jdbc.update("update order_change_closure_attempts set status='STARTED',result_code=null,reason_code=null,finished_at=null,version=version+1 where id=?",a.id()));
        }
        var a=write(()->repository.insertAttempt(attempt()));
        for(String set:List.of("context_digest=decode('00','hex')","trigger_kind='BAD'","operation_id=gen_random_uuid()"))
            rejects(()->jdbc.update("update order_change_closure_attempts set status='COMPLETED',result_code='NOT_CLOSED',finished_at=started_at,version=version+1,"+set+" where id=?",a.id()));
    }
    @Test void attemptRejectsInvalidDigestResultAndFinishTimeOnInsert(){
        for(String tail:List.of("decode('00','hex'),'STARTED',null,null", "decode(repeat('00',32),'hex'),'COMPLETED','BAD',localtimestamp", "decode(repeat('00',32),'hex'),'COMPLETED','NOT_CLOSED',localtimestamp-interval '1 day'")){
            rejects(()->jdbc.update("insert into order_change_closure_attempts(id,operation_id,order_id,new_payment_id,trigger_kind,started_at,created_at,context_digest,status,result_code,finished_at) values(?,?,?,?,'RECOVERY',localtimestamp,localtimestamp,"+tail+")",UUID.randomUUID(),fixture.operation,fixture.order,fixture.payment));
        }
    }
    @Test void runHeartbeatCompletionCountsAndTerminalGuards(){
        for(String state:List.of("SUCCESS","PARTIAL_FAILURE","FAILED","UNKNOWN")){
            var r=write(()->repository.insertRun(run()));
            var heartbeat=new Run(r.id(),r.runKind(),"RUNNING",1,now,null,now.plusSeconds(1),2,0,0,0,0,0,true,null,now);
            assertThat(write(()->repository.updateRun(heartbeat,0))).isTrue();
            int failures=state.equals("SUCCESS")?0:1;
            var finished=new Run(r.id(),r.runKind(),state,2,now,now.plusSeconds(2),now.plusSeconds(1),2,2,1,0,0,failures,true,2000L,now);
            assertThat(write(()->repository.updateRun(finished,1))).isTrue();assertThat(repository.findRun(r.id()).orElseThrow().status()).isEqualTo(state);
            rejects(()->jdbc.update("update order_change_closure_runs set status='RUNNING',finished_at=null,duration_ms=null,version=version+1 where id=?",r.id()));
        }
        var r=write(()->repository.insertRun(run()));
        for(String set:List.of("processed_count=-1","heartbeat_at=started_at-interval '1 second'","status='SUCCESS',finished_at=started_at","processed_count=1","duration_ms=1","status='FAILED',finished_at=started_at,duration_ms=1000","status='FAILED',finished_at=started_at,duration_ms=null"))
            rejects(()->jdbc.update("update order_change_closure_runs set "+set+",version=version+1 where id=?",r.id()));
    }
    @Test void concurrentSameCaseInsertHasExactlyOneWinner()throws Exception{
        var i=issue();var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var jobs=new ArrayList<Future<Boolean>>();for(int n=0;n<2;n++)jobs.add(pool.submit(()->{gate.await();try{write(()->repository.insertIssue(i));return true;}catch(DataAccessException expected){return false;}}));gate.countDown();
            int wins=0;for(var job:jobs)if(job.get(20,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void rollbackLeavesNoAuditRowsOrFinancialChanges(){
        var a=attempt();assertThatThrownBy(()->write(()->{repository.insertAttempt(a);throw new IllegalStateException("rollback test");})).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findAttempt(a.id())).isEmpty();assertThat(snapshot()).isEqualTo(financialBefore);
    }
    @Test void normalWriterRoleCannotMutateEventsAndTriggersAlsoProtectBroadGrants(){
        String role="closure_writer_"+UUID.randomUUID().toString().replace("-","");String schema=fixture.db.schema;
        jdbc.execute("create role "+role+" nologin");
        try{
            jdbc.execute("grant usage on schema "+schema+" to "+role);
            jdbc.execute("grant select,insert on all tables in schema "+schema+" to "+role);
            jdbc.execute("grant usage on all sequences in schema "+schema+" to "+role);
            assertThat(jdbc.queryForObject("select has_table_privilege(?, 'order_change_closure_issue_events','UPDATE,DELETE,TRUNCATE')",Boolean.class,role)).isFalse();
            var i=write(()->repository.insertIssue(issue()));
            write(()->{jdbc.execute("set local role "+role);return jdbc.update("insert into order_change_closure_issue_events(id,issue_id,episode_no,event_kind,actor_type,occurred_at) values(?,?,1,'DETECTED','SYSTEM',?)",UUID.randomUUID(),i.id(),java.sql.Timestamp.from(now));});
            jdbc.execute("grant update,delete,truncate on order_change_closure_issue_events to "+role);
            for(String sql:List.of("update order_change_closure_issue_events set event_kind=event_kind","delete from order_change_closure_issue_events","truncate order_change_closure_issue_events"))
                rejects(()->write(()->{jdbc.execute("set local role "+role);jdbc.execute(sql);return null;}));
        }finally{jdbc.execute("drop owned by "+role);jdbc.execute("drop role "+role);}
    }
}
