package com.example.ecsite.service.payment.safety.payload;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.example.ecsite.service.payment.safety.payload.ObservationLedgerSnapshot.*;
import com.example.ecsite.service.payment.safety.payload.ObservationEventFactory.*;
import com.example.ecsite.service.payment.safety.payload.ObservationOrderContextV1.*;

/** Pure validation/projection. ORDER row order and wall clocks never determine trial order.
 * Persisted takeover history and Assessment truth are caller/repository responsibilities.
 */
public final class ObservationEventLedgerReducer {
    public static final class IntegrityException extends IllegalArgumentException {
        private IntegrityException(){super("Observation ledger integrity error");}
    }
    public record StartedTrial(long targetOrderId,int attemptNo,long epoch,UUID owner,UUID eventUuid,String recordDigest) {}
    /** nextAttempts lists per-target candidates. Any openTrial blocks ALL new starts at repository level.
     * Readiness flags are ledger materials, never Run transitions or financial permits. */
    public record Result(ObservationLedgerCounters counters,long cursor,Optional<StartedTrial> openTrial,
            Map<Long,Outcome> latestOutcomes,Set<Long> unresolvedOrders,Set<Long> acceptedOrders,
            Map<Long,Integer> nextAttempts,boolean allFirstFinished,boolean readyForSuccess,boolean readyForPartialFailure) {
        public Result {
            latestOutcomes=Collections.unmodifiableMap(new TreeMap<>(latestOutcomes));
            unresolvedOrders=Collections.unmodifiableSet(new TreeSet<>(unresolvedOrders));
            acceptedOrders=Collections.unmodifiableSet(new TreeSet<>(acceptedOrders));
            nextAttempts=Collections.unmodifiableMap(new TreeMap<>(nextAttempts));
        }
    }
    private record Key(long target,int attempt) implements Comparable<Key> {
        public int compareTo(Key other){int c=Long.compare(target,other.target);return c==0?Integer.compare(attempt,other.attempt):c;}
    }
    private record Parsed(LedgerEvent event,ObservationOrderContextV1 context) {}
    private static final class Trial {Parsed start;Parsed finish;}
    public Result reduce(ObservationLedgerSnapshot input) {
        try{return checked(Objects.requireNonNull(input));}
        catch(IntegrityException failure){throw failure;}
        catch(RuntimeException failure){throw new IntegrityException();} // never expose input/profile/error text
    }
    private Result checked(ObservationLedgerSnapshot in) {
        ObservationRunDigestsV1.verifyManifest(in.manifest(),in.manifestDigest());
        var scope=in.manifest().scope();
        require(in.currentCursor()>=scope.lowerOrderIdExclusive() && in.currentCursor()<=scope.upperOrderIdInclusive());
        require(in.events().size()<=in.limits().maxEvents() && in.assessments().size()<=in.manifest().targetOrderIds().size());
        var targets=new TreeSet<>(in.manifest().targetOrderIds());
        // Reuse the existing factory's verified manifest binding, not thousands of full profile hashes.
        // ORDER methods always receive the event actor explicitly; this requester is never emitted.
        var factory=new ObservationEventFactory(new ObservationRunRequest(in.runId(),ObservationRunRequest.ExecutionType.DIAGNOSTIC,in.manifest(),ObservationRunRequest.Actor.system("ledger-reducer")));
        var assessments=new HashMap<Long,VerifiedAssessment>();
        for(var a:in.assessments())require(a.runId().equals(in.runId()) && targets.contains(a.orderId()) && assessments.putIfAbsent(a.assessmentId(),a)==null);
        var engine=new PayloadEncodingV1(PaymentSafetyPayloadCodecV1.Limits.event());
        long bytes=new ObservationManifestCodecV1().encode(in.manifest()).getBytes(StandardCharsets.UTF_8).length;
        bytes=Math.addExact(bytes,Math.multiplyExact(256L,in.assessments().size()));require(bytes<=in.limits().maxInputBytes());
        var trials=new TreeMap<Key,Trial>();var uuids=new HashSet<UUID>();
        var epochOwners=new HashMap<Long,UUID>();var takeoverRefs=new HashMap<UUID,TakeoverRef>();
        for(var e:in.events()) {
            require(e!=null && e.record()!=null && e.eventUuid()!=null && uuids.add(e.eventUuid()));
            var r=e.record();
            bytes=Math.addExact(bytes,engine.json(engine.encode(r,AuditEventDigestV1.Record.class)).getBytes(StandardCharsets.UTF_8).length);
            require(bytes<=in.limits().maxInputBytes());
            AuditEventDigestV1.verify(r,e.recordDigest());
            require(in.runId().equals(r.runId()) && "RUN".equals(r.eventScope()) && targets.contains(r.targetOrderId())
                && r.attemptNo()!=null && r.attemptNo()>=1 && r.attemptNo()<=Math.min(3,in.manifest().policy().maxAttempts()));
            boolean started="ORDER_STARTED".equals(r.eventType());require(started || "ORDER_FINISHED".equals(r.eventType()));
            UUID expected=started?ObservationEventIdsV1.orderStarted(in.runId(),r.targetOrderId(),r.attemptNo()):ObservationEventIdsV1.orderFinished(in.runId(),r.targetOrderId(),r.attemptNo());
            require(expected.equals(e.eventUuid()));
            var root=engine.parse(r.payloadJson());
            require(root.isObject() && root.has("orderContext"));
            var context=(ObservationOrderContextV1)engine.decode(root.get("orderContext"),ObservationOrderContextV1.class);
            require(context!=null && in.manifestDigest().equals(context.manifestDigest()));
            owner(epochOwners,context.saveEpoch(),context.saveOwner());
            if(context.takeover()!=null) {
                var ref=context.takeover();owner(epochOwners,ref.epochBefore(),ref.ownerBefore());owner(epochOwners,ref.epochAfter(),ref.ownerAfter());
                var prior=takeoverRefs.putIfAbsent(ref.eventUuid(),ref);require(prior==null || prior.equals(ref));
            }
            var t=trials.computeIfAbsent(new Key(r.targetOrderId(),r.attemptNo()),ignored->new Trial());
            if(started){require(t.start==null);t.start=new Parsed(e,context);}else{require(t.finish==null);t.finish=new Parsed(e,context);}
        }
        var latest=new TreeMap<Long,Outcome>();var unresolved=new TreeSet<Long>();var accepted=new TreeSet<Long>();
        var next=new TreeMap<Long,Integer>();var usedAssessments=new HashSet<Long>();StartedTrial open=null;
        long classified=0,review=0,acquisition=0,assembly=0,persistence=0,interrupted=0,retry=0;
        for(long target:targets) {
            int number=0;boolean settled=false;long previousSaveEpoch=0;
            for(var entry:trials.subMap(new Key(target,1),true,new Key(target,3),true).entrySet()) {
                require(!settled && entry.getKey().attempt()==++number);
                var trial=entry.getValue();require(trial.start!=null);
                var sr=trial.start.event.record();var sc=trial.start.context;
                var actor=new ObservationRunRequest.Actor(ObservationRunRequest.ActorType.valueOf(sr.actorType()),sr.actorAccountId(),sr.actorUsername());
                var start=factory.withOrderContext(factory.orderStarted(target,number,sr.leaseEpoch(),actor,sr.occurredAt()),sc);
                matches(trial.start.event,start);
                require(sc.startEpoch()>=previousSaveEpoch);
                if(number>1)retry++;
                if(trial.finish==null) {
                    require(open==null && number==trials.subMap(new Key(target,1),true,new Key(target,3),true).size());
                    open=new StartedTrial(target,number,sc.startEpoch(),sc.saveOwner(),trial.start.event.eventUuid(),trial.start.event.recordDigest());
                    continue;
                }
                var fr=trial.finish.event.record();var fc=trial.finish.context;var outcome=Outcome.valueOf(fr.outcome());
                AssessmentRef ref=null;
                if(outcome==Outcome.CLASSIFIED) {
                    var a=assessments.get(fr.assessmentId());
                    require(a!=null && a.orderId()==target && a.attemptNo()==number && usedAssessments.add(a.assessmentId()));
                    ref=new AssessmentRef(a.runId(),a.orderId(),a.attemptNo(),a.assessmentId(),a.recordDigest());
                    classified++;if(a.disposition()==Disposition.REVIEW)review++;
                }
                require(fc.startEpoch()==sc.startEpoch() && trial.start.event.eventUuid().equals(fc.startedUuid()) && trial.start.event.recordDigest().equals(fc.startedDigest()));
                if(outcome==Outcome.INTERRUPTED) {
                    require(fc.takeover()!=null);
                    if(fc.takeover().epochBefore()==sc.startEpoch())require(fc.takeover().ownerBefore().equals(sc.saveOwner()));
                }else require(fc.saveOwner().equals(sc.saveOwner()));
                var finish=factory.withOrderContext(factory.orderFinished(target,number,fr.leaseEpoch(),new ObservationRunRequest.Actor(ObservationRunRequest.ActorType.valueOf(fr.actorType()),fr.actorAccountId(),fr.actorUsername()),fr.occurredAt(),outcome,ref),fc);
                matches(trial.finish.event,finish);previousSaveEpoch=fc.saveEpoch();latest.put(target,outcome);
                switch(outcome) {
                    case CLASSIFIED,NOT_FOUND -> {accepted.add(target);unresolved.remove(target);settled=true;}
                    case ACQUISITION_FAILED -> {acquisition++;unresolved.add(target);}
                    case ASSEMBLY_FAILED -> {assembly++;unresolved.add(target);}
                    case PERSISTENCE_FAILED -> {persistence++;unresolved.add(target);}
                    case INTERRUPTED -> {interrupted++;unresolved.add(target);}
                }
            }
            if(!settled && number<Math.min(3,in.manifest().policy().maxAttempts()) && (open==null || open.targetOrderId()!=target))next.put(target,number+1);
        }
        require(usedAssessments.size()==assessments.size());
        long cursor=scope.lowerOrderIdExclusive();boolean all=true;
        for(long target:targets){if(!latest.containsKey(target)){all=false;break;}cursor=target;}
        if(all)cursor=scope.upperOrderIdInclusive();
        require(in.currentCursor()<=cursor);
        long notFound=latest.values().stream().filter(o->o==Outcome.NOT_FOUND).count();
        var counters=new ObservationLedgerCounters(latest.size(),classified,review,acquisition,assembly,persistence,interrupted,retry,unresolved.size(),notFound,0);
        return new Result(counters,cursor,Optional.ofNullable(open),latest,unresolved,accepted,next,all,all && open==null && unresolved.isEmpty(),all && open==null && !unresolved.isEmpty() && next.isEmpty());
    }
    private static void matches(LedgerEvent actual,Event expected) {
        require(actual.eventUuid().equals(expected.eventUuid()) && actual.recordDigest().equals(expected.recordDigest()) && AuditEventDigestV1.sameContent(actual.record(),expected.record()));
    }
    private static void owner(Map<Long,UUID> owners,long epoch,UUID owner){var prior=owners.putIfAbsent(epoch,owner);require(prior==null || prior.equals(owner));}
    private static void require(boolean condition){if(!condition)throw new IntegrityException();}
}
