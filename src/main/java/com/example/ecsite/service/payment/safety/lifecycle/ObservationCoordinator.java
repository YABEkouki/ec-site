package com.example.ecsite.service.payment.safety.lifecycle;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import com.example.ecsite.service.payment.safety.payload.*;
import static com.example.ecsite.service.payment.safety.lifecycle.ObservationRunReceipt.Status.*;
import static com.example.ecsite.service.payment.safety.payload.ObservationLifecycleCommands.Action.*;

/** Explicit, synchronous maintenance API. Not a bean, scheduler or financial execution path.
 * One worker at a time; caller supplies an observer-only DataSource and a stable worker UUID.
 * Same-process unknown commands are retained. A restarted worker never resumes a live open trial:
 * it waits for expiry/takeover and records INTERRUPTED using DB evidence before a fresh attempt.
 */
public final class ObservationCoordinator {
    public record Result(ObservationRunReceipt.Status status,ObservationRunReceipt.RunView current,ObservationEventLedgerReducer.Result ledger) {}
    private record State(ObservationRunRequest request,JdbcObservationOrderAttemptRepository.State value) {}
    private record Pending(ObservationOrderAttempts.Prepared order,ObservationLifecycleCommands.Command lifecycle) {}
    private final DataSource source;
    private final ObservationRunLifecycle lifecycle;
    private final ObservationOrderAttempts attempts;
    private final JdbcObservationRunRepository runs=new JdbcObservationRunRepository();
    private final JdbcObservationOrderAttemptRepository orders=new JdbcObservationOrderAttemptRepository();
    private final Map<UUID,Pending> pending=new HashMap<>();
    private final Map<UUID,ObservationOrderAttempts.Prepared> active=new HashMap<>();
    public ObservationCoordinator(DataSource observerSource){source=Objects.requireNonNull(observerSource);lifecycle=new ObservationRunLifecycle(source);attempts=new ObservationOrderAttempts(source);}

    public synchronized Result run(UUID runId,UUID worker,ObservationRunRequest.Actor actor) {
        Objects.requireNonNull(runId);Objects.requireNonNull(worker);Objects.requireNonNull(actor);
        State state=null;
        try {
            var previous=pending.get(runId);
            if(previous!=null){
                if(previous.order()!=null){
                    var resolved=attempts.resolve(previous.order());
                    if(resolved.status()==NOT_APPLIED){
                        pending.remove(runId); // uncertainty is resolved; a definitive rejection must not pin this envelope
                        resolved=attempts.execute(previous.order());
                        if(resolved.status()==STORAGE_FAILED && active.containsKey(runId)
                            && Set.of("CLASSIFIED","NOT_FOUND").contains(previous.order().event().record().outcome())) {
                            resolved=attempts.execute(attempts.failureAfterSave(resolved,active.get(runId),actor));
                        }
                    }
                    if(!saved(resolved.status())) {
                        if(retain(resolved.status()))pending.put(runId,new Pending(resolved.prepared(),null));
                        return new Result(resolved.status(),resolved.current(),resolved.ledger());
                    }
                    remember(resolved);pending.remove(runId);
                }else {
                    var resolved=lifecycle.resolve(previous.lifecycle());
                    if(resolved.status()==NOT_APPLIED){pending.remove(runId);resolved=lifecycle.execute(previous.lifecycle());}
                    if(previous.lifecycle().action()==HEARTBEAT && resolved.status()==LEASE_LOST)pending.remove(runId);
                    if(!saved(resolved.status()) && resolved.status()!=OBSERVED){
                        if(retain(resolved.status()))pending.put(runId,previous);
                        return new Result(resolved.status(),resolved.current(),null);
                    }
                    pending.remove(runId);
                }
            }
            // Manifest bounds this loop: at most 3 trials per target; integrity failures never spin.
            for(int step=0;step<10000;step++) {
                state=read(runId);var request=state.request();var view=state.value().row().view();var ledger=state.value().ledger();
                if(!view.status().equals("RUNNING")) {
                    if(Set.of("SUCCESS","PARTIAL_FAILURE","FAILED").contains(view.status())){verifyTerminal(state);return result(OBSERVED,state);}
                    return result(STATE_CONFLICT,state);
                }
                if(!view.leaseUntil().isAfter(view.observedAt())) {
                    var takeover=lifecycle.prepare(request,TAKEOVER,UUID.randomUUID(),view.owner(),worker,view.epoch(),view.version(),actor);
                    var receipt=lifecycle.execute(takeover);if(!saved(receipt.status()))return lifecycleStop(runId,takeover,receipt,state);
                    continue;
                }
                if(!worker.equals(view.owner()))return result(LEASE_LOST,state);
                if(ledger.openTrial().isPresent()) {
                    var open=ledger.openTrial().orElseThrow();var start=active.get(runId);
                    if(open.epoch()<view.epoch()) {
                        var recovery=attempts.prepareRecovery(request,worker,view.epoch(),view.version(),actor);
                        var receipt=attempts.execute(recovery);if(!saved(receipt.status()))return orderStop(receipt,state);active.remove(runId);continue;
                    }
                    if(start==null || !start.event().eventUuid().equals(open.eventUuid()) || !start.event().recordDigest().equals(open.recordDigest()))return result(UNKNOWN,state);
                    var completion=attempts.prepareOutcome(start,UUID.randomUUID(),view.version(),actor);
                    var receipt=attempts.execute(completion);
                    // STORAGE_FAILED is emitted only after acknowledged rollback. Never turn UNKNOWN
                    // or a failed failure-event INSERT into a new failure command.
                    if(receipt.status()==STORAGE_FAILED && Set.of("CLASSIFIED","NOT_FOUND").contains(completion.event().record().outcome())) {
                        receipt=attempts.execute(attempts.failureAfterSave(receipt,start,actor));
                    }
                    if(!saved(receipt.status()))return orderStop(receipt,state);
                    active.remove(runId);continue;
                }
                if(ledger.readyForSuccess() || ledger.readyForPartialFailure()) {
                    var action=ledger.readyForSuccess()?OBSERVATION_SUCCESS:OBSERVATION_PARTIAL_FAILURE;
                    var finish=lifecycle.prepare(request,action,UUID.randomUUID(),worker,null,view.epoch(),view.version(),actor);
                    var receipt=lifecycle.execute(finish);if(!saved(receipt.status()))return lifecycleStop(runId,finish,receipt,state);continue;
                }
                // Heartbeat is Run-only, before waiting for any Order lock. Its observed version
                // is reloaded; it never grants authority to a subsequent financial operation.
                var heartbeat=lifecycle.prepare(request,HEARTBEAT,UUID.randomUUID(),worker,worker,view.epoch(),view.version(),actor);
                var beat=lifecycle.execute(heartbeat);if(!saved(beat.status()))return lifecycleStop(runId,heartbeat,beat,state);
                state=read(runId);view=state.value().row().view();ledger=state.value().ledger();
                if(ledger.nextAttempts().isEmpty())return result(INTEGRITY_ERROR,state);
                long target=ledger.nextAttempts().keySet().iterator().next();
                var start=attempts.prepareStart(request,target,worker,view.epoch(),view.version(),actor);
                var receipt=attempts.execute(start);if(!saved(receipt.status()))return orderStop(receipt,state);remember(receipt);
            }
            return result(INTEGRITY_ERROR,state);
        }catch(ObservationOrderAttempts.Rejected rejected){if(rejected.status()==UNKNOWN)active.remove(runId);return result(rejected.status(),state);}
        catch(SQLException failure){return result(UNKNOWN,state);}
        catch(JdbcObservationRunRepository.IntegrityFailure | IllegalArgumentException failure){return result(INTEGRITY_ERROR,state);}
        catch(IllegalStateException failure){return result(UNKNOWN,state);}
    }
    private State read(UUID id)throws SQLException {
        try(var c=source.getConnection()){
            // Discovery uses fixed conservative observer timeouts; all writes use the frozen policy.
            ObservationRunLifecycle.configure(c,ObservationRunRequest.Policy.defaults());
            try {
                var row=runs.lock(c,id).orElseThrow(()->new ObservationOrderAttempts.Rejected(STATE_CONFLICT));
                var state=new State(row.request(),orders.state(c,row.request()));c.rollback();return state;
            }finally{c.rollback();}
        }
    }
    private void verifyTerminal(State state)throws SQLException {
        var view=state.value().row().view();var ledger=state.value().ledger();
        if(view.status().equals("SUCCESS") && !ledger.readyForSuccess() || view.status().equals("PARTIAL_FAILURE") && !ledger.readyForPartialFailure())throw new JdbcObservationRunRepository.IntegrityFailure();
        try(var c=source.getConnection()){
            ObservationRunLifecycle.configure(c,state.request().manifest().policy());
            try {
                var stored=new JdbcObservationLifecycleEventStore().find(c,ObservationEventIdsV1.runFinished(view.id())).orElseThrow(JdbcObservationRunRepository.IntegrityFailure::new);
                var context=ObservationLifecycleContextV1.fromEvent(stored.record());
                if(context.versionAfter()!=view.version() || context.epochAfter()!=view.epoch())throw new JdbcObservationRunRepository.IntegrityFailure();
                var r=stored.record();var actor=new ObservationRunRequest.Actor(ObservationRunRequest.ActorType.valueOf(r.actorType()),r.actorAccountId(),r.actorUsername());
                var factory=new ObservationEventFactory(state.request());
                var expected=factory.withLifecycleContext(factory.finished(ObservationEventFactory.TerminalStatus.valueOf(view.status()),view.epoch(),actor,r.occurredAt()),context);
                if(!new JdbcObservationLifecycleEventStore().matches(stored,expected))throw new JdbcObservationRunRepository.IntegrityFailure();
                c.rollback();
            }finally{c.rollback();}
        }
    }
    private void remember(ObservationOrderAttempts.Result receipt){
        var p=receipt.prepared();if("ORDER_STARTED".equals(p.event().record().eventType()))active.put(p.request().requestId(),p);else active.remove(p.request().requestId());
    }
    private Result orderStop(ObservationOrderAttempts.Result receipt,State state){
        if(retain(receipt.status()))pending.put(state.request().requestId(),new Pending(receipt.prepared(),null));
        return new Result(receipt.status(),receipt.current(),receipt.ledger());
    }
    private Result lifecycleStop(UUID id,ObservationLifecycleCommands.Command command,ObservationRunReceipt receipt,State state){
        if(retain(receipt.status()))pending.put(id,new Pending(null,command));return new Result(receipt.status(),receipt.current(),state.value().ledger());
    }
    private static boolean retain(ObservationRunReceipt.Status status){return Set.of(UNKNOWN,STORAGE_FAILED,NOT_APPLIED).contains(status);}
    private static boolean saved(ObservationRunReceipt.Status status){return status==APPLIED || status==REPLAYED;}
    private static Result result(ObservationRunReceipt.Status status,State state){return new Result(status,state==null?null:state.value().row().view(),state==null?null:state.value().ledger());}
}
