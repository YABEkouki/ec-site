package com.example.ecsite.service.payment.safety.payload;

import java.time.Instant;
import java.util.*;
import com.example.ecsite.service.payment.safety.payload.ObservationRunRequest.Actor;

/** Pure OBSERVE_ONLY V56 shape factory. No clock/DB/config access, INSERT, lease change or authority.
 * Callers must retain occurredAt for a replay, and later repositories must check both UUID/content
 * and fencing. A valid factory result proves shape only, not that a state transition occurred.
 * Payload is closed metadata: no caller JSON, free-form errors, profiles or snapshot bodies.
 */
public final class ObservationEventFactory {
    public enum Outcome { CLASSIFIED, NOT_FOUND, ACQUISITION_FAILED, ASSEMBLY_FAILED, PERSISTENCE_FAILED, INTERRUPTED }
    public enum TerminalStatus { SUCCESS, PARTIAL_FAILURE, FAILED }
    public record AssessmentRef(UUID runId,long orderId,int attemptNo,long assessmentId,String recordDigest) {
        public AssessmentRef {
            if(runId==null || orderId<=0 || attemptNo<=0 || assessmentId<=0 || recordDigest==null || !recordDigest.matches("[0-9a-f]{64}"))throw PayloadEncodingV1.invalid();
        }
    }
    /** Factory-only construction; cannot wrap an arbitrary AuditEventDigest record as a valid Event. */
    public static final class Event {
        private final UUID eventUuid;
        private final AuditEventDigestV1.Record record;
        private final String recordDigest;
        private Event(UUID eventUuid,AuditEventDigestV1.Record record) {
            this.eventUuid=eventUuid;this.record=record;this.recordDigest=AuditEventDigestV1.sha256(record);
        }
        public UUID eventUuid(){return eventUuid;}
        public AuditEventDigestV1.Record record(){return record;}
        public String recordDigest(){return recordDigest;}
        public void verify(UUID expectedUuid,String expectedDigest) {
            if(!eventUuid.equals(expectedUuid))throw PayloadEncodingV1.invalid();
            AuditEventDigestV1.verify(record,expectedDigest);
        }
        @Override public String toString(){return "ObservationEvent[redacted]";}
    }
    private final ObservationRunRequest request;
    private final String manifestDigest;
    private final Set<Long> targets;
    public ObservationEventFactory(ObservationRunRequest request) {
        if(request==null)throw PayloadEncodingV1.invalid();
        this.request=request;this.manifestDigest=ObservationRunDigestsV1.manifestDigest(request.manifest());
        this.targets=Set.copyOf(request.manifest().targetOrderIds());
    }
    public Event started(Instant time) {
        return event("STARTED",ObservationEventIdsV1.runStarted(request.requestId()),1,request.requester(),time,null,null,null,null,null,null);
    }
    public Event orderStarted(long target,int attempt,long epoch,Actor actor,Instant time) {
        target(target,attempt);
        return event("ORDER_STARTED",ObservationEventIdsV1.orderStarted(request.requestId(),target,attempt),epoch,actor,time,target,attempt,null,null,null,null);
    }
    public Event orderFinished(long target,int attempt,long epoch,Actor actor,Instant time,Outcome outcome,AssessmentRef assessment) {
        target(target,attempt);
        if(outcome==null || (outcome==Outcome.CLASSIFIED)!=(assessment!=null))throw PayloadEncodingV1.invalid();
        if(assessment!=null && (!assessment.runId().equals(request.requestId()) || assessment.orderId()!=target || assessment.attemptNo()!=attempt))throw PayloadEncodingV1.invalid();
        return event("ORDER_FINISHED",ObservationEventIdsV1.orderFinished(request.requestId(),target,attempt),epoch,actor,time,target,attempt,outcome,assessment,null,null);
    }
    public Event paused(UUID controlRequest,long epoch,Actor actor,Instant time) {
        return event("PAUSED",ObservationEventIdsV1.paused(request.requestId(),controlRequest),epoch,actor,time,null,null,null,null,controlRequest,null);
    }
    public Event resumed(UUID controlRequest,long epoch,Actor actor,Instant time) {
        if(epoch<2)throw PayloadEncodingV1.invalid();
        return event("RESUMED",ObservationEventIdsV1.resumed(request.requestId(),controlRequest),epoch,actor,time,null,null,null,null,controlRequest,null);
    }
    public Event leaseTaken(long newEpoch,Actor actor,Instant time) {
        return event("LEASE_TAKEN",ObservationEventIdsV1.leaseTaken(request.requestId(),newEpoch),newEpoch,actor,time,null,null,null,null,null,null);
    }
    public Event finished(TerminalStatus terminal,long epoch,Actor actor,Instant time) {
        if(terminal==null)throw PayloadEncodingV1.invalid();
        return event("FINISHED",ObservationEventIdsV1.runFinished(request.requestId()),epoch,actor,time,null,null,null,null,null,terminal);
    }
    public Event runFailed(long epoch,Actor actor,Instant time){return finished(TerminalStatus.FAILED,epoch,actor,time);}
    /** Adds a closed typed command binding without changing the legacy V1 factory outputs/UUIDs. */
    public Event withLifecycleContext(Event base,ObservationLifecycleContextV1 context) {
        if(base==null || context==null || !base.record().runId().equals(request.requestId()))throw PayloadEncodingV1.invalid();
        var r=base.record();context.validateFor(r.eventType(),r.leaseEpoch());
        var engine=new PayloadEncodingV1(PaymentSafetyPayloadCodecV1.Limits.event());
        var payload=engine.parse(r.payloadJson());
        if(!payload.isObject() || payload.has("lifecycleContext"))throw PayloadEncodingV1.invalid();
        ((tools.jackson.databind.node.ObjectNode)payload).set("lifecycleContext",engine.encode(context,ObservationLifecycleContextV1.class));
        var record=new AuditEventDigestV1.Record(r.eventScope(),r.eventType(),r.runId(),r.caseId(),r.orderId(),r.targetOrderId(),r.attemptNo(),r.leaseEpoch(),r.assessmentId(),
            r.caseVersionBefore(),r.caseVersionAfter(),r.fromStatus(),r.toStatus(),r.outcome(),r.actorType(),r.actorAccountId(),r.actorUsername(),r.occurredAt(),r.comment(),r.errorStage(),r.errorCode(),r.sqlState(),r.errorSummary(),r.payloadFormatVersion(),engine.json(payload));
        return new Event(base.eventUuid(),record);
    }
    private void target(long target,int attempt) {
        if(!targets.contains(target) || attempt<1 || attempt>request.manifest().policy().maxAttempts())throw PayloadEncodingV1.invalid();
    }
    private Event event(String type,UUID uuid,long epoch,Actor actor,Instant time,Long target,Integer attempt,
            Outcome outcome,AssessmentRef assessment,UUID control,TerminalStatus terminal) {
        if(epoch<=0 || actor==null || time==null || time.getNano()%1000!=0)throw PayloadEncodingV1.invalid();
        var engine=new PayloadEncodingV1(PaymentSafetyPayloadCodecV1.Limits.event());
        var payload=engine.object();payload.put("version",1);payload.put("identityFormatVersion",ObservationEventIdsV1.FORMAT_VERSION);
        payload.put("mode","OBSERVE_ONLY");payload.put("financialResumption","NOT_EVALUATED");payload.put("manifestDigest",manifestDigest);
        if(control!=null)payload.put("controlRequestId",control.toString());
        if(terminal!=null)payload.put("terminalStatus",terminal.name());
        if(assessment!=null)payload.put("assessmentRecordDigest",assessment.recordDigest());
        var record=new AuditEventDigestV1.Record("RUN",type,request.requestId(),null,assessment==null?null:target,
            target,attempt,epoch,assessment==null?null:assessment.assessmentId(),null,null,null,null,
            outcome==null?null:outcome.name(),actor.type().name(),actor.accountId(),actor.username(),time,
            null,null,null,null,null,(short)1,engine.json(payload));
        return new Event(uuid,record);
    }
}
