package com.example.ecsite.service.payment.safety.lifecycle;

import java.sql.*;
import java.time.*;
import java.util.*;
import com.example.ecsite.service.payment.safety.payload.*;
import com.example.ecsite.service.payment.safety.payload.ObservationLifecycleCommands.*;
import static com.example.ecsite.service.payment.safety.lifecycle.ObservationRunReceipt.Status.*;

/** Internal, tentative JDBC operations. Never commits/rolls back/closes a caller Connection.
 * Owns only Run -> lifecycle Event locks, never Order/Payment. No arbitrary finish/count/cursor API.
 */
final class JdbcObservationRunRepository {
    static final class IntegrityFailure extends RuntimeException { IntegrityFailure(){super("Observation audit integrity error");} }
    private static final String RUNS="payment_safety_classification_runs";
    static final List<String> COUNTS=List.of("processed_order_count","classified_order_count","review_order_count","acquisition_failure_attempt_count","assembly_failure_attempt_count","persistence_failure_attempt_count","interrupted_attempt_count","retry_attempt_count","unresolved_failure_order_count","not_found_order_count","skipped_order_count");
    private final JdbcObservationLifecycleEventStore events=new JdbcObservationLifecycleEventStore();
    record Row(ObservationRunRequest request,ObservationRunReceipt.RunView view,boolean zeroCounts,List<Long> counts) {}
    static Instant now(Connection c) throws SQLException {
        try(var q=c.prepareStatement("select clock_timestamp()");var r=q.executeQuery()){if(!r.next())throw new SQLException("Database time unavailable","08000");return r.getObject(1,OffsetDateTime.class).toInstant();}
    }
    void requireTx(Connection c) throws SQLException {
        if(c.getAutoCommit() || c.isReadOnly() || c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLNonTransientException("Writable READ COMMITTED transaction required","25000");
        try(var q=c.prepareStatement("select current_setting('transaction_isolation'),current_setting('transaction_read_only')");var r=q.executeQuery()) {
            if(!r.next() || !"read committed".equals(r.getString(1)) || !"off".equals(r.getString(2)))throw new SQLNonTransientException("Actual writable READ COMMITTED transaction required","25000");
        }
    }
    ObservationRunReceipt execute(Connection c,Command command) throws SQLException {
        requireTx(c);
        if(command.action()==Action.CREATE)return create(c,command);
        var optional=lock(c,command.request().requestId());
        if(optional.isEmpty())return receipt(command,STATE_CONFLICT,null);
        var row=optional.get();
        if(!row.request().equals(command.request()))return receipt(command,CONTENT_CONFLICT,row);
        var replay=replay(c,command,row);if(replay!=null)return replay;
        var i=command.intent();var v=row.view();Instant databaseNow=now(c);
        if(command.action()==Action.RESUME) {
            if(!v.status().equals("PAUSED") || v.owner()!=null || v.leaseUntil()!=null)return receipt(command,STATE_CONFLICT,row);
            if(v.epoch()!=i.epochBefore())return receipt(command,LEASE_LOST,row);
        }else {
            if(!v.status().equals("RUNNING"))return receipt(command,STATE_CONFLICT,row);
            if(!Objects.equals(v.owner(),i.ownerBefore()) || v.epoch()!=i.epochBefore())return receipt(command,LEASE_LOST,row);
            boolean expired=!v.leaseUntil().isAfter(databaseNow);
            if(command.action()==Action.TAKEOVER?!expired:expired)return receipt(command,LEASE_LOST,row);
        }
        if(v.version()!=i.expectedVersion())return receipt(command,VERSION_CONFLICT,row);
        if(command.action()==Action.PAUSE || command.action()==Action.EMPTY_SUCCESS || command.action()==Action.BEFORE_OBSERVATION_FAILED) {
            if(hasObservationData(c,v.id()))return receipt(command,UNSUPPORTED_PHASE,row);
        }
        if(command.action()==Action.EMPTY_SUCCESS || command.action()==Action.BEFORE_OBSERVATION_FAILED) {
            if(!row.zeroCounts() || v.cursor()!=row.request().manifest().scope().lowerOrderIdExclusive()
                || (command.action()==Action.EMPTY_SUCCESS && !row.request().manifest().targetOrderIds().isEmpty()))return receipt(command,UNSUPPORTED_PHASE,row);
        }
        String changes=switch(command.action()) {
            case HEARTBEAT -> "heartbeat_at=clock_timestamp(),lease_until=greatest(lease_until,clock_timestamp()+make_interval(secs=>?)),version=version+1";
            case TAKEOVER,RESUME -> "lease_owner=?,lease_epoch=lease_epoch+1,heartbeat_at=clock_timestamp(),lease_until=clock_timestamp()+make_interval(secs=>?),status='RUNNING',version=version+1";
            case PAUSE -> "status='PAUSED',lease_owner=null,lease_until=null,version=version+1";
            case EMPTY_SUCCESS -> "status='SUCCESS',cursor_order_id=upper_order_id_inclusive,lease_owner=null,lease_until=null,finished_at=clock_timestamp(),version=version+1";
            case BEFORE_OBSERVATION_FAILED -> "status='FAILED',lease_owner=null,lease_until=null,finished_at=clock_timestamp(),version=version+1";
            default -> throw new IntegrityFailure();
        };
        String predicate=command.action()==Action.RESUME?"status='PAUSED' and lease_owner is null and lease_until is null":
            "status='RUNNING' and lease_owner=? and lease_until"+(command.action()==Action.TAKEOVER?"<=":" >")+"clock_timestamp()";
        String sql="update "+RUNS+" set "+changes+" where id=? and mode='OBSERVE_ONLY' and guard_activation_ref is null and lease_epoch=? and version=? and "+predicate+" returning id";
        boolean applied;
        try(var q=c.prepareStatement(sql)) {
            int p=1;
            if(command.action()==Action.TAKEOVER || command.action()==Action.RESUME)q.setObject(p++,i.ownerAfter());
            if(command.action()==Action.HEARTBEAT || command.action()==Action.TAKEOVER || command.action()==Action.RESUME)q.setInt(p++,row.request().manifest().policy().leaseSeconds());
            q.setObject(p++,v.id());q.setLong(p++,i.epochBefore());q.setLong(p++,i.expectedVersion());
            if(command.action()!=Action.RESUME)q.setObject(p,i.ownerBefore());
            try(var r=q.executeQuery()){applied=r.next();}
        }
        if(!applied)return receipt(command,LEASE_LOST,row); // caller must roll back, including future tentative operations
        if(command.event().isPresent())events.insert(c,command.event().orElseThrow());
        return receipt(command,APPLIED,lock(c,v.id()).orElseThrow(IntegrityFailure::new));
    }
    ObservationRunReceipt resolve(Connection c,Command command) throws SQLException {
        requireTx(c);var optional=lock(c,command.request().requestId());
        // An invisible uncommitted CREATE cannot be disproved by SELECT. Only same-UUID INSERT replay can arbitrate.
        if(optional.isEmpty())return receipt(command,command.action()==Action.CREATE?UNKNOWN:STATE_CONFLICT,null);
        var row=optional.get();if(!row.request().equals(command.request()))return receipt(command,CONTENT_CONFLICT,row);
        if(command.action()==Action.HEARTBEAT) {
            var v=row.view();var i=command.intent();boolean live=v.status().equals("RUNNING") && Objects.equals(v.owner(),i.ownerBefore()) && v.epoch()==i.epochBefore() && v.leaseUntil().isAfter(now(c));
            return receipt(command,live?OBSERVED:LEASE_LOST,row); // observation, never a historical heartbeat commit claim
        }
        var replay=replay(c,command,row);return replay!=null?replay:receipt(command,NOT_APPLIED,row);
    }
    private ObservationRunReceipt create(Connection c,Command command) throws SQLException {
        var request=command.request();var m=request.manifest();var scope=m.scope();var actor=request.requester();
        String sql="insert into "+RUNS+"(id,request_digest,execution_type,mode,classification_contract_version,classifier_impl_version,fingerprint_format_version,contract_manifest_digest,contract_manifest,scope_kind,lower_order_id_exclusive,upper_order_id_inclusive,cursor_order_id,target_order_id,status,lease_owner,lease_epoch,lease_until,heartbeat_at,version,started_at,requested_by_type,requested_by_account_id,requested_by_username) select ?,?,'DIAGNOSTIC','OBSERVE_ONLY',?,?,?,?,?::jsonb,?,?,?,?,?,'RUNNING',?,1,t.now+make_interval(secs=>?),t.now,0,?,?,?,? from (select clock_timestamp() as now) t on conflict (id) do nothing returning id";
        boolean inserted;
        try(var q=c.prepareStatement(sql)) {
            int p=1;q.setObject(p++,request.requestId());q.setString(p++,ObservationRunDigestsV1.requestDigest(request));
            q.setString(p++,m.classificationContractVersion());q.setString(p++,m.classifierImplVersion());q.setShort(p++,m.fingerprintFormatVersion());q.setString(p++,ObservationRunDigestsV1.manifestDigest(m));q.setString(p++,new ObservationManifestCodecV1().encode(m));
            q.setString(p++,scope.kind().name());q.setLong(p++,scope.lowerOrderIdExclusive());q.setLong(p++,scope.upperOrderIdInclusive());q.setLong(p++,scope.lowerOrderIdExclusive());q.setObject(p++,scope.targetOrderId());
            q.setObject(p++,command.intent().ownerAfter());q.setInt(p++,m.policy().leaseSeconds());q.setObject(p++,command.intent().occurredAt().atOffset(ZoneOffset.UTC));
            q.setString(p++,actor.type().name());q.setObject(p++,actor.accountId());q.setString(p,actor.username());try(var r=q.executeQuery()){inserted=r.next();}
        }
        var row=lock(c,request.requestId()).orElseThrow(IntegrityFailure::new);
        if(!row.request().equals(request))return receipt(command,CONTENT_CONFLICT,row);
        if(!inserted) {
            var replay=replay(c,command,row);if(replay==null)throw new IntegrityFailure();return replay;
        }
        events.insert(c,command.event().orElseThrow());return receipt(command,APPLIED,row);
    }
    private ObservationRunReceipt replay(Connection c,Command command,Row row) throws SQLException {
        if(command.event().isEmpty())return null;
        var expected=command.event().orElseThrow();var found=events.find(c,expected.eventUuid());
        if(found.isEmpty())return null;
        return receipt(command,events.matches(found.get(),expected)?REPLAYED:CONTENT_CONFLICT,row);
    }
    private boolean hasObservationData(Connection c,UUID id) throws SQLException {
        try(var q=c.prepareStatement("select exists(select 1 from payment_safety_audit_events where run_id=? and event_type in ('ORDER_STARTED','ORDER_FINISHED')) or exists(select 1 from payment_safety_assessments where run_id=?)")) {
            q.setObject(1,id);q.setObject(2,id);try(var r=q.executeQuery()){if(!r.next())throw new IntegrityFailure();return r.getBoolean(1);}
        }
    }
    Optional<Row> lock(Connection c,UUID id) throws SQLException {
        try(var q=c.prepareStatement("select * from "+RUNS+" where id=? for no key update")) {
            q.setObject(1,id);try(var r=q.executeQuery()) {
                if(!r.next())return Optional.empty();
                try {
                    if(!"OBSERVE_ONLY".equals(r.getString("mode")) || r.getString("guard_activation_ref")!=null || !"DIAGNOSTIC".equals(r.getString("execution_type")))throw new IntegrityFailure();
                    var m=new ObservationManifestCodecV1().decode(r.getString("contract_manifest"));
                    var actor=new ObservationRunRequest.Actor(ObservationRunRequest.ActorType.valueOf(r.getString("requested_by_type")),r.getObject("requested_by_account_id",Long.class),r.getString("requested_by_username"));
                    var request=new ObservationRunRequest(id,ObservationRunRequest.ExecutionType.DIAGNOSTIC,m,actor);
                    ObservationRunDigestsV1.verifyRequest(request,r.getString("request_digest"));ObservationRunDigestsV1.verifyManifest(m,r.getString("contract_manifest_digest"));
                    var s=m.scope();
                    if(!m.classificationContractVersion().equals(r.getString("classification_contract_version")) || !m.classifierImplVersion().equals(r.getString("classifier_impl_version")) || m.fingerprintFormatVersion()!=r.getShort("fingerprint_format_version")
                        || !s.kind().name().equals(r.getString("scope_kind")) || s.lowerOrderIdExclusive()!=r.getLong("lower_order_id_exclusive") || s.upperOrderIdInclusive()!=r.getLong("upper_order_id_inclusive")
                        || !Objects.equals(s.targetOrderId(),r.getObject("target_order_id",Long.class)) || r.getObject("resume_of_run_id")!=null)throw new IntegrityFailure();
                    var counts=new ArrayList<Long>();boolean zero=true;for(var count:COUNTS){long value=r.getLong(count);counts.add(value);if(value!=0)zero=false;}
                    var lease=r.getObject("lease_until",OffsetDateTime.class);
                    var view=new ObservationRunReceipt.RunView(id,r.getString("status"),r.getObject("lease_owner",UUID.class),r.getLong("lease_epoch"),r.getLong("version"),lease==null?null:lease.toInstant(),r.getLong("cursor_order_id"),now(c));
                    return Optional.of(new Row(request,view,zero,List.copyOf(counts)));
                }catch(IllegalArgumentException | NullPointerException corrupt){throw new IntegrityFailure();}
            }
        }
    }
    private ObservationRunReceipt receipt(Command command,ObservationRunReceipt.Status status,Row row) {
        return new ObservationRunReceipt(status,row==null?null:row.view(),command.event().map(ObservationEventFactory.Event::eventUuid).orElse(null),command.event().map(ObservationEventFactory.Event::recordDigest).orElse(null),null);
    }
}
