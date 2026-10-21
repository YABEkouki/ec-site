package com.example.ecsite.service.payment.safety.lifecycle;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.time.Instant;
import com.example.ecsite.service.payment.safety.payload.*;
import com.example.ecsite.service.payment.safety.payload.ObservationOrderContextV1.*;
import static com.example.ecsite.service.payment.safety.lifecycle.ObservationRunReceipt.Status.*;

/** Explicit single-worker observer API, not a bean or automatic entry point. Supply a dedicated
 * observer DataSource. Retain Prepared envelopes across unknown commits; on worker restart resolve
 * retained envelopes before advancing. The in-memory UNKNOWN gate is NOT a durable recovery journal.
 */
public final class ObservationOrderAttempts {
    public static final class Rejected extends IllegalStateException {
        private final ObservationRunReceipt.Status status;
        Rejected(ObservationRunReceipt.Status status){super("Observation order request rejected: "+status);this.status=status;}
        public ObservationRunReceipt.Status status(){return status;}
    }
    public static final class Prepared {
        private final ObservationRunRequest request;
        private final ObservationOrderCommands.Command command,start;
        private final ObservationLifecycleCommands.Command takeover;
        private final long expectedVersion;
        private final JdbcObservationAssessmentStore.Candidate assessment;
        private Prepared(ObservationRunRequest r,ObservationOrderCommands.Command command,ObservationOrderCommands.Command start,ObservationLifecycleCommands.Command takeover,long version) {
            this(r,command,start,takeover,version,null);
        }
        private Prepared(ObservationRunRequest r,ObservationOrderCommands.Command command,ObservationOrderCommands.Command start,ObservationLifecycleCommands.Command takeover,long version,JdbcObservationAssessmentStore.Candidate assessment) {
            this.assessment=assessment;
            if(version<0)throw new Rejected(VERSION_CONFLICT);
            request=r;this.command=command;this.start=start;this.takeover=takeover;expectedVersion=version;
        }
        public ObservationEventFactory.Event event(){return command.event();}
        public ObservationRunRequest request(){return request;}
        public UUID owner(){return command.context().saveOwner();}
        public long epoch(){return command.context().saveEpoch();}
        public long expectedVersion(){return expectedVersion;}
        @Override public String toString(){return "PreparedOrderAttempt[redacted]";}
    }
    public record Result(ObservationRunReceipt.Status status,ObservationRunReceipt.RunView current,Prepared prepared,
            ObservationEventLedgerReducer.Result ledger,String sqlState) {}
    private final DataSource source;
    private final JdbcObservationOrderAttemptRepository repository=new JdbcObservationOrderAttemptRepository();
    private final Map<UUID,Prepared> unknown=new HashMap<>();
    public ObservationOrderAttempts(DataSource observerSource){source=Objects.requireNonNull(observerSource);}

    /** Tentative selection only. execute repeats allocation/eligibility in the actual INSERT transaction. */
    public synchronized Prepared prepareStart(ObservationRunRequest request,long target,UUID owner,long epoch,long version,ObservationRunRequest.Actor actor) {
        gate(request.requestId());
        try(var c=connection(request)) {try {
            var state=repository.stateForWrite(c,request,owner,epoch,version,null);
            var attempt=state.ledger().nextAttempts().get(target);
            if(attempt==null || state.ledger().openTrial().isPresent())throw new Rejected(UNSUPPORTED_PHASE);
            var command=ObservationOrderCommands.started(request,target,attempt,epoch,owner,actor,JdbcObservationRunRepository.now(c));
            c.rollback();return new Prepared(request,command,command,null,version);
        }finally{rollback(c);}}catch(SQLException failure){throw new Rejected(UNKNOWN);}
        catch(JdbcObservationRunRepository.IntegrityFailure | IllegalArgumentException failure){throw new Rejected(INTEGRITY_ERROR);}
    }
    /** Explicitly rolls back the original failed unit of work and requires an acknowledged rollback.
     * The caller still owns that connection. A boolean claim is deliberately not accepted.
     * Use only for a known failed, uncommitted unit; an original COMMIT with unknown outcome must
     * be reconciled first and must never be converted to a failure by calling this method.
     */
    public synchronized Prepared prepareFailureAfterRollback(Connection failedTransaction,Prepared started,long version,Failure failure,ObservationRunRequest.Actor actor) {
        Objects.requireNonNull(started);gate(started.request.requestId());
        if(!started.event().record().eventType().equals("ORDER_STARTED") || failure==null)throw new Rejected(UNSUPPORTED_PHASE);
        var outcome=switch(failure.stage()) {
            case ACQUISITION -> ObservationEventFactory.Outcome.ACQUISITION_FAILED;
            case ASSEMBLY,CLASSIFICATION -> ObservationEventFactory.Outcome.ASSEMBLY_FAILED;
            case PERSISTENCE -> ObservationEventFactory.Outcome.PERSISTENCE_FAILED;
            default -> throw new Rejected(UNSUPPORTED_PHASE);
        };
        try {
            if(failedTransaction==null || failedTransaction.isClosed() || failedTransaction.getAutoCommit())throw new Rejected(STATE_CONFLICT);
            failedTransaction.rollback();
            var command=ObservationOrderCommands.finished(started.command,started.epoch(),started.owner(),actor,time(started.request),outcome,null,failure,null);
            return new Prepared(started.request,command,started.command,null,version);
        }catch(SQLException failureToRollback){throw new Rejected(UNKNOWN);}
    }
    /** Reconstructs only the stored STARTED envelope; never reuses the old worker's business Snapshot. */
    public synchronized Prepared prepareInterrupted(ObservationRunRequest request,ObservationLifecycleCommands.Command takeover,long version,ObservationRunRequest.Actor actor) {
        gate(request.requestId());var ref=TakeoverRef.from(request,takeover);
        try(var c=connection(request)) {try {
            var state=repository.stateForWrite(c,request,ref.ownerAfter(),ref.epochAfter(),version,null);repository.takeover(c,takeover);
            var start=repository.pendingStart(state);
            var command=ObservationOrderCommands.finished(start,ref.epochAfter(),ref.ownerAfter(),actor,JdbcObservationRunRepository.now(c),ObservationEventFactory.Outcome.INTERRUPTED,null,new Failure(Stage.RECOVERY,Code.WORKER_INTERRUPTED),ref);
            c.rollback();return new Prepared(request,command,start,takeover,version);
        }finally{rollback(c);}}catch(SQLException failure){throw new Rejected(UNKNOWN);}
        catch(JdbcObservationRunRepository.IntegrityFailure | IllegalArgumentException failure){throw new Rejected(INTEGRITY_ERROR);}
    }
    /** Freezes replay identity. execute re-reads locked facts in the actual save transaction. */
    public synchronized Prepared prepareObserved(Prepared started,UUID assessmentRequestId,long version,ObservationRunRequest.Actor actor) {
        Objects.requireNonNull(started);gate(started.request.requestId());
        if(!started.event().record().eventType().equals("ORDER_STARTED"))throw new Rejected(UNSUPPORTED_PHASE);
        try(var c=connection(started.request)) {try {
            var store=new JdbcObservationAssessmentStore();
            var record=store.observe(c,started.request,started.event().record().targetOrderId(),started.event().record().attemptNo());
            var state=repository.stateForWrite(c,started.request,started.owner(),started.epoch(),version,null);
            repository.startedMatches(state,started.command);
            var candidate=store.prepare(c,assessmentRequestId,record);
            var ref=new ObservationEventFactory.AssessmentRef(record.runId(),record.orderId(),record.attemptNo(),candidate.id(),candidate.payloads().recordDigest());
            var command=ObservationOrderCommands.finished(started.command,started.epoch(),started.owner(),actor,JdbcObservationRunRepository.now(c),ObservationEventFactory.Outcome.CLASSIFIED,ref,null,null);
            c.rollback();return new Prepared(started.request,command,started.command,null,version,candidate);
        }finally{rollback(c);}}catch(SQLException failure){throw new Rejected(UNKNOWN);}
        catch(JdbcObservationRunRepository.IntegrityFailure | IllegalArgumentException failure){throw new Rejected(INTEGRITY_ERROR);}
    }
    private ObservationRunReceipt.Status replay(Connection c,Prepared command)throws SQLException {
        var event=repository.replay(c,command.event());
        if(command.assessment==null)return event;
        var assessment=new JdbcObservationAssessmentStore().replay(c,command.request,command.assessment);
        if(event==CONTENT_CONFLICT || assessment==CONTENT_CONFLICT)return CONTENT_CONFLICT;
        if(event!=assessment)throw new JdbcObservationRunRepository.IntegrityFailure();
        return event;
    }
    public synchronized Result execute(Prepared command) {
        Objects.requireNonNull(command);
        var pending=unknown.get(command.request.requestId());if(pending!=null && !same(pending,command))return result(UNKNOWN,null,command,null,null);
        if(command.assessment!=null){var prior=resolve(command);if(prior.status()!=NOT_APPLIED)return prior;}
        Connection c=null;boolean commitSent=false,readingBusiness=false;
        try {
            c=connection(command.request);
            readingBusiness=command.assessment!=null;
            var observed=command.assessment==null?null:new JdbcObservationAssessmentStore().observe(c,command.request,command.event().record().targetOrderId(),command.event().record().attemptNo());
            readingBusiness=false;
            var state=repository.stateForWrite(c,command.request,command.owner(),command.epoch(),command.expectedVersion,command.event());
            var replay=replay(c,command);
            if(replay!=NOT_APPLIED){c.rollback();return settle(result(replay,state.row().view(),command,state.ledger(),null));}
            repository.fence(c,state,command.owner(),command.epoch(),command.expectedVersion);
            if(command.event().record().eventType().equals("ORDER_STARTED"))repository.next(state,command.event().record().targetOrderId(),command.event().record().attemptNo());
            else {repository.startedMatches(state,command.start);if(command.takeover!=null)repository.takeover(c,command.takeover);}
            if(command.assessment!=null){
                if(!command.assessment.record().equals(observed))throw new Rejected(CONTENT_CONFLICT);
                new JdbcObservationAssessmentStore().insert(c,command.assessment);
                repository.insertClassified(c,command.event());
            }else repository.insert(c,command.event());
            var rows=new ArrayList<>(state.events());rows.add(new ObservationLedgerSnapshot.LedgerEvent(command.event().eventUuid(),command.event().recordDigest(),command.event().record()));
            var after=repository.reduce(command.request,rows,state.row().view().cursor(),command.assessment==null?state.assessments():new JdbcObservationAssessmentStore().references(c,command.request));
            var updated=repository.update(c,state,command,after);
            commitSent=true;c.commit();
            return settle(result(APPLIED,updated,command,after,null));
        }catch(Rejected rejection){
            if(!rollback(c)){close(c);c=null;return resolve(command);}
            if(readingBusiness){close(c);c=null;return reconcileReadFailure(command,rejection.status(),null);}
            return settle(result(rejection.status(),null,command,null,null));
        }
        catch(JdbcObservationRunRepository.IntegrityFailure | IllegalArgumentException corrupt){
            if(!rollback(c)){close(c);c=null;return resolve(command);}
            if(readingBusiness){close(c);c=null;return reconcileReadFailure(command,INTEGRITY_ERROR,null);}
            return settle(result(INTEGRITY_ERROR,null,command,null,null));
        }
        catch(SQLException failure) {
            boolean acknowledged=rollback(c);String state=safeState(failure);
            if(readingBusiness && acknowledged){close(c);c=null;return reconcileReadFailure(command,STORAGE_FAILED,state);}
            if(!commitSent && acknowledged && state!=null && !state.startsWith("08") && !Set.of("55P03","57014","23505").contains(state))return settle(result(STORAGE_FAILED,null,command,null,state));
            close(c);c=null;return resolve(command);
        }finally{close(c);}
    }
    private Result reconcileReadFailure(Prepared command,ObservationRunReceipt.Status failure,String sqlState) {
        var checked=resolve(command);
        return checked.status()==NOT_APPLIED?settle(result(failure,checked.current(),command,checked.ledger(),sqlState)):checked;
    }
    public synchronized Result resolve(Prepared command) {
        Objects.requireNonNull(command);var pending=unknown.get(command.request.requestId());
        if(pending!=null && !same(pending,command))return result(UNKNOWN,null,command,null,null);
        try(var c=connection(command.request)) {try {
            var state=repository.state(c,command.request);var status=replay(c,command);c.rollback();
            return settle(result(status,state.row().view(),command,state.ledger(),null));
        }finally{rollback(c);}}catch(Rejected failure){return settle(result(failure.status(),null,command,null,null));}
        catch(JdbcObservationRunRepository.IntegrityFailure | IllegalArgumentException corrupt){return settle(result(INTEGRITY_ERROR,null,command,null,null));}
        catch(SQLException failure){return settle(result(UNKNOWN,null,command,null,safeState(failure)));}
    }
    private Result settle(Result result) {
        UUID run=result.prepared.request.requestId();
        if(result.status==UNKNOWN)unknown.put(run,result.prepared);
        else if(Set.of(APPLIED,REPLAYED,NOT_APPLIED).contains(result.status))unknown.remove(run);
        return result;
    }
    private void gate(UUID run){if(unknown.containsKey(run))throw new Rejected(UNKNOWN);}
    private boolean same(Prepared a,Prepared b){return Objects.equals(a.assessment==null?null:a.assessment.requestId(),b.assessment==null?null:b.assessment.requestId()) && a.expectedVersion==b.expectedVersion && a.request.equals(b.request) && a.event().eventUuid().equals(b.event().eventUuid()) && a.event().recordDigest().equals(b.event().recordDigest()) && AuditEventDigestV1.sameContent(a.event().record(),b.event().record());}
    private Connection connection(ObservationRunRequest request)throws SQLException {
        var c=source.getConnection();try{ObservationRunLifecycle.configure(c,request.manifest().policy());return c;}catch(SQLException | RuntimeException failure){close(c);throw failure;}
    }
    private Instant time(ObservationRunRequest request)throws SQLException{try(var c=connection(request)){var time=JdbcObservationRunRepository.now(c);c.rollback();return time;}}
    private static Result result(ObservationRunReceipt.Status status,ObservationRunReceipt.RunView row,Prepared command,ObservationEventLedgerReducer.Result ledger,String sqlState){return new Result(status,row,command,ledger,sqlState);}
    private static String safeState(SQLException e){String s=e.getSQLState();return s!=null && s.matches("[0-9A-Z]{5}")?s:null;}
    private static boolean rollback(Connection c){if(c==null)return false;try{c.rollback();return true;}catch(SQLException ignored){return false;}}
    private static void close(Connection c){if(c!=null)try{c.close();}catch(SQLException ignored){/* never undo acknowledged commit */}}
}
