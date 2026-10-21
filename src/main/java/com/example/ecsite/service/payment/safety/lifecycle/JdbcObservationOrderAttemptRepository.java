package com.example.ecsite.service.payment.safety.lifecycle;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.example.ecsite.service.payment.safety.payload.*;
import com.example.ecsite.service.payment.safety.payload.ObservationLedgerSnapshot.*;
import com.example.ecsite.service.payment.safety.payload.ObservationOrderContextV1.TakeoverRef;
import static com.example.ecsite.service.payment.safety.lifecycle.ObservationRunReceipt.Status.*;

/** Run-only operations. Never acquires an Order/Payment/Case lock, commits or owns a connection. */
final class JdbcObservationOrderAttemptRepository {
    private final JdbcObservationRunRepository runs=new JdbcObservationRunRepository();
    private final JdbcObservationLifecycleEventStore events=new JdbcObservationLifecycleEventStore();
    private final ObservationEventLedgerReducer reducer=new ObservationEventLedgerReducer();
    record State(JdbcObservationRunRepository.Row row,ObservationEventLedgerReducer.Result ledger,List<LedgerEvent> events,List<VerifiedAssessment> assessments) {}
    State state(Connection c,ObservationRunRequest request) throws SQLException {return stateForWrite(c,request,null,0,0,null);}
    State stateForWrite(Connection c,ObservationRunRequest request,UUID owner,long epoch,long version,ObservationEventFactory.Event replayExpected) throws SQLException {
        runs.requireTx(c);var row=runs.lock(c,request.requestId()).orElseThrow(()->reject(STATE_CONFLICT));
        if(!row.request().equals(request))throw reject(CONTENT_CONFLICT);
        if(owner!=null && (replayExpected==null || replay(c,replayExpected)==NOT_APPLIED))fence(c,row,owner,epoch,version);
        var assessments=new JdbcObservationAssessmentStore().references(c,request);
        var ledger=new ArrayList<LedgerEvent>();long bytes=0;
        try(var q=c.prepareStatement("select * from payment_safety_audit_events where run_id=? and event_scope='RUN' and event_type in ('ORDER_STARTED','ORDER_FINISHED') order by id limit 6001")) {
            q.setFetchSize(100);q.setObject(1,request.requestId());try(var r=q.executeQuery()) {while(r.next()) {
                if(ledger.size()==6000)throw new JdbcObservationRunRepository.IntegrityFailure();
                var e=events.read(r);bytes+=e.record().payloadJson().getBytes(StandardCharsets.UTF_8).length+4096L;
                if(bytes>64L*1024*1024)throw new JdbcObservationRunRepository.IntegrityFailure();
                ledger.add(new LedgerEvent(e.uuid(),e.digest(),e.record()));
            }}
        }
        var result=reduce(request,ledger,row.view().cursor(),assessments);
        if(!counts(result.counters()).equals(row.counts()))throw new JdbcObservationRunRepository.IntegrityFailure();
        boolean initialEmpty=request.manifest().targetOrderIds().isEmpty() && row.view().cursor()==request.manifest().scope().lowerOrderIdExclusive();
        if(!initialEmpty && row.view().cursor()!=result.cursor())throw new JdbcObservationRunRepository.IntegrityFailure();
        return new State(row,result,List.copyOf(ledger),assessments);
    }
    ObservationEventLedgerReducer.Result reduce(ObservationRunRequest request,List<LedgerEvent> rows,long cursor) {
        return reduce(request,rows,cursor,List.of());
    }
    ObservationEventLedgerReducer.Result reduce(ObservationRunRequest request,List<LedgerEvent> rows,long cursor,List<VerifiedAssessment> assessments) {
        return reducer.reduce(new ObservationLedgerSnapshot(request.requestId(),request.manifest(),ObservationRunDigestsV1.manifestDigest(request.manifest()),rows,assessments,cursor,Limits.defaults()));
    }
    void fence(Connection c,State state,UUID owner,long epoch,long version) throws SQLException {
        fence(c,state.row(),owner,epoch,version);
    }
    private void fence(Connection c,JdbcObservationRunRepository.Row row,UUID owner,long epoch,long version) throws SQLException {
        var v=row.view();
        if(!v.status().equals("RUNNING"))throw reject(STATE_CONFLICT);
        if(!Objects.equals(v.owner(),owner) || v.epoch()!=epoch || !v.leaseUntil().isAfter(JdbcObservationRunRepository.now(c)))throw reject(LEASE_LOST);
        if(v.version()!=version)throw reject(VERSION_CONFLICT);
    }
    void next(State state,long target,int attempt) {
        if(state.ledger().openTrial().isPresent() || !Objects.equals(state.ledger().nextAttempts().get(target),attempt))throw reject(UNSUPPORTED_PHASE);
    }
    void startedMatches(State state,ObservationOrderCommands.Command start) {
        var trial=state.ledger().openTrial().orElseThrow(()->reject(UNSUPPORTED_PHASE));
        if(!trial.eventUuid().equals(start.event().eventUuid()) || !trial.recordDigest().equals(start.event().recordDigest()))throw reject(CONTENT_CONFLICT);
    }
    ObservationOrderCommands.Command pendingStart(State state) {
        var open=state.ledger().openTrial().orElseThrow(()->reject(UNSUPPORTED_PHASE));
        var e=state.events().stream().filter(x->x.eventUuid().equals(open.eventUuid())).findFirst().orElseThrow(JdbcObservationRunRepository.IntegrityFailure::new);
        var r=e.record();var actor=new ObservationRunRequest.Actor(ObservationRunRequest.ActorType.valueOf(r.actorType()),r.actorAccountId(),r.actorUsername());
        return ObservationOrderCommands.started(state.row().request(),open.targetOrderId(),open.attemptNo(),open.epoch(),open.owner(),actor,r.occurredAt());
    }
    void takeover(Connection c,ObservationLifecycleCommands.Command command) throws SQLException {
        if(command==null || command.action()!=ObservationLifecycleCommands.Action.TAKEOVER)throw reject(STATE_CONFLICT);
        var expected=command.event().orElseThrow();var actual=events.find(c,expected.eventUuid()).orElseThrow(()->reject(UNSUPPORTED_PHASE));
        if(!events.matches(actual,expected))throw reject(CONTENT_CONFLICT);
    }
    TakeoverRef takeoverReference(Connection c,ObservationRunRequest request,long epoch)throws SQLException {
        var stored=events.find(c,ObservationEventIdsV1.leaseTaken(request.requestId(),epoch)).orElseThrow(JdbcObservationRunRepository.IntegrityFailure::new);
        var r=stored.record();var context=ObservationLifecycleContextV1.fromEvent(r);
        if(!"LEASE_TAKEN".equals(r.eventType()) || !request.requestId().equals(r.runId()))throw new JdbcObservationRunRepository.IntegrityFailure();
        var actor=new ObservationRunRequest.Actor(ObservationRunRequest.ActorType.valueOf(r.actorType()),r.actorAccountId(),r.actorUsername());
        var factory=new ObservationEventFactory(request);
        if(!events.matches(stored,factory.withLifecycleContext(factory.leaseTaken(epoch,actor,r.occurredAt()),context)))throw new JdbcObservationRunRepository.IntegrityFailure();
        return new TakeoverRef(r.runId(),stored.uuid(),stored.digest(),ObservationRunDigestsV1.manifestDigest(request.manifest()),context.epochBefore(),context.epochAfter(),context.ownerBefore(),context.ownerAfter());
    }
    void takeover(Connection c,ObservationRunRequest request,TakeoverRef expected)throws SQLException {
        if(!expected.equals(takeoverReference(c,request,expected.epochAfter())))throw reject(CONTENT_CONFLICT);
    }
    ObservationRunReceipt.Status replay(Connection c,ObservationEventFactory.Event expected) throws SQLException {
        var found=events.find(c,expected.eventUuid());return found.isEmpty()?NOT_APPLIED:events.matches(found.get(),expected)?REPLAYED:CONTENT_CONFLICT;
    }
    void insert(Connection c,ObservationEventFactory.Event event) throws SQLException {insert(c,event,false);}
    void insertClassified(Connection c,ObservationEventFactory.Event event)throws SQLException {insert(c,event,true);}
    private void insert(Connection c,ObservationEventFactory.Event event,boolean classified)throws SQLException {
        var r=event.record();
        if(classified && (!"RUN".equals(r.eventScope()) || !"ORDER_FINISHED".equals(r.eventType()) || !"CLASSIFIED".equals(r.outcome()) || r.orderId()==null || r.assessmentId()==null || r.caseId()!=null))throw reject(UNSUPPORTED_PHASE);
        if(!classified && (!"RUN".equals(r.eventScope()) || !(r.eventType().equals("ORDER_STARTED") || (r.eventType().equals("ORDER_FINISHED") && Set.of("ACQUISITION_FAILED","ASSEMBLY_FAILED","PERSISTENCE_FAILED","INTERRUPTED","NOT_FOUND").contains(r.outcome())))
            || r.orderId()!=null || r.assessmentId()!=null || r.caseId()!=null))throw reject(UNSUPPORTED_PHASE);
        try(var q=c.prepareStatement("insert into payment_safety_audit_events(event_uuid,record_digest,event_scope,event_type,run_id,target_order_id,attempt_no,lease_epoch,outcome,actor_type,actor_account_id,actor_username,occurred_at,error_stage,error_code,payload_format_version,payload,order_id,assessment_id) values (?,?,'RUN',?,?,?,?,?,?,?,?,?,?,?,?,1,?::jsonb,?,?)")) {
            q.setObject(1,event.eventUuid());q.setString(2,event.recordDigest());q.setString(3,r.eventType());q.setObject(4,r.runId());q.setObject(5,r.targetOrderId());q.setObject(6,r.attemptNo());q.setObject(7,r.leaseEpoch());q.setString(8,r.outcome());q.setString(9,r.actorType());q.setObject(10,r.actorAccountId());q.setString(11,r.actorUsername());q.setObject(12,r.occurredAt().atOffset(ZoneOffset.UTC));q.setString(13,r.errorStage());q.setString(14,r.errorCode());q.setString(15,r.payloadJson());q.setObject(16,r.orderId());q.setObject(17,r.assessmentId());
            if(q.executeUpdate()!=1)throw new JdbcObservationRunRepository.IntegrityFailure();
        }
    }
    ObservationRunReceipt.RunView update(Connection c,State before,ObservationOrderAttempts.Prepared command,ObservationEventLedgerReducer.Result after) throws SQLException {
        if(after.cursor()<before.row().view().cursor())throw new JdbcObservationRunRepository.IntegrityFailure();
        String assignments=String.join(",",JdbcObservationRunRepository.COUNTS.stream().map(name->name+"=?").toList());
        try(var q=c.prepareStatement("update payment_safety_classification_runs set "+assignments+",cursor_order_id=?,version=version+1 where id=? and mode='OBSERVE_ONLY' and guard_activation_ref is null and status='RUNNING' and lease_owner=? and lease_epoch=? and version=? and lease_until>clock_timestamp() returning id,status,lease_owner,lease_epoch,version,lease_until,cursor_order_id,clock_timestamp() as observed_at,"+String.join(",",JdbcObservationRunRepository.COUNTS))) {
            int p=1;for(long count:counts(after.counters()))q.setLong(p++,count);q.setLong(p++,after.cursor());q.setObject(p++,command.request().requestId());q.setObject(p++,command.owner());q.setLong(p++,command.epoch());q.setLong(p,command.expectedVersion());
            try(var r=q.executeQuery()) {
                if(!r.next())throw reject(LEASE_LOST);
                var expected=counts(after.counters());
                for(int n=0;n<expected.size();n++)if(r.getLong(JdbcObservationRunRepository.COUNTS.get(n))!=expected.get(n))throw new JdbcObservationRunRepository.IntegrityFailure();
                if(r.getLong("cursor_order_id")!=after.cursor())throw new JdbcObservationRunRepository.IntegrityFailure();
                return new ObservationRunReceipt.RunView(r.getObject("id",UUID.class),r.getString("status"),r.getObject("lease_owner",UUID.class),r.getLong("lease_epoch"),r.getLong("version"),r.getObject("lease_until",OffsetDateTime.class).toInstant(),r.getLong("cursor_order_id"),r.getObject("observed_at",OffsetDateTime.class).toInstant());
            }
        }
    }
    static List<Long> counts(ObservationLedgerCounters x){return List.of(x.processedOrderCount(),x.classifiedOrderCount(),x.reviewOrderCount(),x.acquisitionFailureAttemptCount(),x.assemblyFailureAttemptCount(),x.persistenceFailureAttemptCount(),x.interruptedAttemptCount(),x.retryAttemptCount(),x.unresolvedFailureOrderCount(),x.notFoundOrderCount(),x.skippedOrderCount());}
    static ObservationOrderAttempts.Rejected reject(ObservationRunReceipt.Status status){return new ObservationOrderAttempts.Rejected(status);}
}
