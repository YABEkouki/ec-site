package com.example.ecsite.service.payment.safety.payload;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.payment.safety.payload.ObservationEventFactory.Outcome.*;
import static com.example.ecsite.service.payment.safety.payload.ObservationOrderContextV1.*;
import static com.example.ecsite.service.payment.safety.payload.ObservationLedgerSnapshot.*;

class ObservationEventLedgerReducerTest {
    final ObservationRunRequest request=ObservationRunContractsV1Test.request();
    final UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000009");
    final Instant time=Instant.parse("2026-10-20T00:00:00.123456Z");
    final ObservationEventLedgerReducer reducer=new ObservationEventLedgerReducer();
    ObservationOrderCommands.Command start(long id,int attempt){return ObservationOrderCommands.started(request,id,attempt,1,owner,request.requester(),time);}
    ObservationOrderCommands.Command end(ObservationOrderCommands.Command s,ObservationEventFactory.Outcome o) {
        Failure f=switch(o){case ACQUISITION_FAILED->new Failure(Stage.ACQUISITION,Code.READ_FAILED);case ASSEMBLY_FAILED->new Failure(Stage.ASSEMBLY,Code.UNREPRESENTABLE_INPUT);case PERSISTENCE_FAILED->new Failure(Stage.PERSISTENCE,Code.STORAGE_FAILED);default->null;};
        return ObservationOrderCommands.finished(s,1,owner,request.requester(),time,o,null,f,null);
    }
    LedgerEvent event(ObservationOrderCommands.Command c){return new LedgerEvent(c.event().eventUuid(),c.event().recordDigest(),c.event().record());}
    ObservationLedgerSnapshot snapshot(List<LedgerEvent> events,List<VerifiedAssessment> assessments,long cursor) {
        return new ObservationLedgerSnapshot(request.requestId(),request.manifest(),ObservationRunDigestsV1.manifestDigest(request.manifest()),events,assessments,cursor,Limits.defaults());
    }
    ObservationEventLedgerReducer.Result reduce(ObservationOrderCommands.Command... commands){return reducer.reduce(snapshot(Arrays.stream(commands).map(this::event).toList(),List.of(),0));}
    @Test void emptyAndStartedOnlyDoNotInventProcessedOrders() {
        var empty=reduce();assertThat(empty.counters().processedOrderCount()).isZero();assertThat(empty.cursor()).isZero();assertThat(empty.nextAttempts()).containsEntry(1L,1).containsEntry(10L,1);
        var open=reduce(start(1,1));assertThat(open.openTrial()).isPresent();assertThat(open.counters().processedOrderCount()).isZero();assertThat(open.cursor()).isZero();assertThat(open.nextAttempts()).doesNotContainKey(1L);
    }
    @Test void failedOrderRemainsUnresolvedDuringRetryAndSuccessRemovesIt() {
        var a=start(1,1);var failed=end(a,ACQUISITION_FAILED);var b=start(1,2);
        var open=reduce(a,failed,b);assertThat(open.counters().processedOrderCount()).isEqualTo(1);assertThat(open.counters().retryAttemptCount()).isEqualTo(1);assertThat(open.unresolvedOrders()).containsExactly(1L);assertThat(open.cursor()).isEqualTo(1);
        var done=reduce(a,failed,b,end(b,NOT_FOUND));assertThat(done.counters().notFoundOrderCount()).isEqualTo(1);assertThat(done.counters().acquisitionFailureAttemptCount()).isEqualTo(1);assertThat(done.unresolvedOrders()).isEmpty();assertThat(done.acceptedOrders()).containsExactly(1L);
    }
    @Test void threeFailuresExhaustAttemptsAndAllElevenCountersAreExact() {
        var a=start(1,1);var b=start(1,2);var c=start(1,3);
        var result=reduce(a,end(a,ACQUISITION_FAILED),b,end(b,ASSEMBLY_FAILED),c,end(c,PERSISTENCE_FAILED));
        assertThat(result.counters()).isEqualTo(new ObservationLedgerCounters(1,0,0,1,1,1,0,2,1,0,0));assertThat(result.nextAttempts()).doesNotContainKey(1L);
    }
    @Test void rejectsOpenConflictOrphanDuplicateAndAttemptGap() {
        var a=start(1,1);var done=end(a,NOT_FOUND);
        for(var list:List.of(List.of(event(a),event(start(10,1))),List.of(event(done)),List.of(event(a),event(a)),List.of(event(a),event(done),event(done)),List.of(event(start(1,2))),List.of(event(a),event(done),event(start(1,2)))))
            assertThatThrownBy(()->reducer.reduce(snapshot(list,List.of(),0))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
    }
    @Test void classifiedRequiresExactVerifiedAssessmentAndReviewIsSubset() {
        var a=start(1,1);var ref=new ObservationEventFactory.AssessmentRef(request.requestId(),1,1,7,"ab".repeat(32));
        var done=ObservationOrderCommands.finished(a,1,owner,request.requester(),time,CLASSIFIED,ref,null,null);
        var rows=List.of(event(a),event(done));
        assertThatThrownBy(()->reducer.reduce(snapshot(rows,List.of(),0))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
        var verified=new VerifiedAssessment(request.requestId(),1,1,7,"ab".repeat(32),Disposition.REVIEW);
        var r=reducer.reduce(snapshot(rows,List.of(verified),0));assertThat(r.counters().classifiedOrderCount()).isEqualTo(1);assertThat(r.counters().reviewOrderCount()).isEqualTo(1);
        var wrong=new VerifiedAssessment(request.requestId(),1,1,7,"cd".repeat(32),Disposition.REVIEW);
        assertThatThrownBy(()->reducer.reduce(snapshot(rows,List.of(wrong),0))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
    }
    @Test void failedRetryThenClassifiedClearsUnresolvedAndPreservesAttemptCounters() {
        var first=start(1,1);var second=start(1,2);var ref=new ObservationEventFactory.AssessmentRef(request.requestId(),1,2,7,"ab".repeat(32));
        var done=ObservationOrderCommands.finished(second,1,owner,request.requester(),time,CLASSIFIED,ref,null,null);
        var verified=new VerifiedAssessment(request.requestId(),1,2,7,"ab".repeat(32),Disposition.NORMAL_NULL);
        var r=reducer.reduce(snapshot(List.of(event(first),event(end(first,ASSEMBLY_FAILED)),event(second),event(done)),List.of(verified),0));
        assertThat(r.counters()).isEqualTo(new ObservationLedgerCounters(1,1,0,0,1,0,0,1,0,0,0));assertThat(r.acceptedOrders()).containsExactly(1L);
    }
    @Test void fullPrefixReachesUpperAndSavedCursorAheadIsRejected() {
        var a=start(1,1);var b=start(10,1);var result=reduce(a,end(a,NOT_FOUND),b,end(b,NOT_FOUND));
        assertThat(result.cursor()).isEqualTo(100);assertThat(result.allFirstFinished()).isTrue();assertThat(result.readyForSuccess()).isTrue();
        assertThatThrownBy(()->reducer.reduce(snapshot(List.of(event(a)),List.of(),1))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
    }
    @Test void inputPermutationIsDeterministicAndReturnedCollectionsAreImmutable() {
        var a=start(1,1);var b=start(10,1);var rows=new ArrayList<>(List.of(event(a),event(end(a,ACQUISITION_FAILED)),event(b),event(end(b,NOT_FOUND))));
        var expected=reducer.reduce(snapshot(rows,List.of(),0));
        for(int seed=0;seed<20;seed++){Collections.shuffle(rows,new Random(seed));assertThat(reducer.reduce(snapshot(rows,List.of(),0))).isEqualTo(expected);}
        assertThatThrownBy(()->expected.unresolvedOrders().add(99L)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void uuidDigestAndManifestTamperingAreRejected() {
        var a=event(start(1,1));
        for(var row:List.of(new LedgerEvent(UUID.randomUUID(),a.recordDigest(),a.record()),new LedgerEvent(a.eventUuid(),"ab".repeat(32),a.record())))
            assertThatThrownBy(()->reducer.reduce(snapshot(List.of(row),List.of(),0))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
        var good=snapshot(List.of(a),List.of(),0);var bad=new ObservationLedgerSnapshot(good.runId(),good.manifest(),"ab".repeat(32),good.events(),good.assessments(),0,good.limits());
        assertThatThrownBy(()->reducer.reduce(bad)).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
    }
    LedgerEvent replace(LedgerEvent source,String field,Object value) throws Exception {
        var components=AuditEventDigestV1.Record.class.getRecordComponents();var args=new Object[components.length];
        for(int n=0;n<components.length;n++)args[n]=components[n].getName().equals(field)?value:components[n].getAccessor().invoke(source.record());
        var record=AuditEventDigestV1.Record.class.getDeclaredConstructor(Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(args);
        return new LedgerEvent(source.eventUuid(),AuditEventDigestV1.sha256(record),record);
    }
    @Test void interruptedTrialAndRetryUseNewEpochAndOwner() {
        var a=start(1,1);UUID next=UUID.randomUUID();
        var takeover=ObservationLifecycleCommands.prepare(request,ObservationLifecycleCommands.Action.TAKEOVER,UUID.randomUUID(),owner,next,1,0,request.requester(),time);
        var interrupted=ObservationOrderCommands.finished(a,2,next,request.requester(),time,INTERRUPTED,null,new Failure(Stage.RECOVERY,Code.WORKER_INTERRUPTED),TakeoverRef.from(request,takeover));
        var b=ObservationOrderCommands.started(request,1,2,2,next,request.requester(),time);
        var done=ObservationOrderCommands.finished(b,2,next,request.requester(),time,NOT_FOUND,null,null,null);
        var r=reduce(a,interrupted,b,done);assertThat(r.counters()).isEqualTo(new ObservationLedgerCounters(1,0,0,0,0,0,1,1,0,1,0));
    }
    @Test void gapsPrefixAndFailedRetriesNeverRegressCursor() {
        var manifest=new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.range(0,100),List.of(10L,30L,90L),request.manifest().classificationContractVersion(),request.manifest().classifierImplVersion(),(short)1,request.manifest().captureProfiles(),Map.of(),Map.of(),request.manifest().policy());
        var run=new ObservationRunRequest(request.requestId(),request.executionType(),manifest,request.requester());
        var a=ObservationOrderCommands.started(run,10,1,1,owner,run.requester(),time);var b=ObservationOrderCommands.started(run,30,1,1,owner,run.requester(),time);
        var endA=ObservationOrderCommands.finished(a,1,owner,run.requester(),time,NOT_FOUND,null,null,null);
        var endB=ObservationOrderCommands.finished(b,1,owner,run.requester(),time,ACQUISITION_FAILED,null,new Failure(Stage.ACQUISITION,Code.READ_FAILED),null);
        var retry=ObservationOrderCommands.started(run,30,2,1,owner,run.requester(),time);
        var rows=List.of(event(a),event(endA),event(b),event(endB),event(retry));
        var r=reducer.reduce(new ObservationLedgerSnapshot(run.requestId(),manifest,ObservationRunDigestsV1.manifestDigest(manifest),rows,List.of(),30,Limits.defaults()));
        assertThat(r.cursor()).isEqualTo(30);assertThat(r.unresolvedOrders()).containsExactly(30L);assertThat(r.allFirstFinished()).isFalse();
        // An out-of-prefix completion does not jump over an untouched manifest target.
        var c=ObservationOrderCommands.started(run,90,1,1,owner,run.requester(),time);var cEnd=ObservationOrderCommands.finished(c,1,owner,run.requester(),time,NOT_FOUND,null,null,null);
        r=reducer.reduce(new ObservationLedgerSnapshot(run.requestId(),manifest,ObservationRunDigestsV1.manifestDigest(manifest),List.of(event(c),event(cEnd)),List.of(),0,Limits.defaults()));
        assertThat(r.cursor()).isZero();
    }
    @Test void emptyManifestUsesUpperBoundaryAndExhaustionGivesTerminalMaterialsOnly() {
        var m=request.manifest();var empty=new ObservationRunRequest.Manifest(m.scope(),List.of(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),m.verificationProfiles(),m.timeOrigins(),m.policy());
        var r=reducer.reduce(new ObservationLedgerSnapshot(request.requestId(),empty,ObservationRunDigestsV1.manifestDigest(empty),List.of(),List.of(),0,Limits.defaults()));
        assertThat(r.cursor()).isEqualTo(100);assertThat(r.allFirstFinished()).isTrue();assertThat(r.readyForSuccess()).isTrue();
        var rows=new ArrayList<LedgerEvent>();
        for(long id:List.of(1L,10L))for(int attempt=1;attempt<=3;attempt++){var start=start(id,attempt);rows.add(event(start));rows.add(event(end(start,PERSISTENCE_FAILED)));}
        r=reducer.reduce(snapshot(rows,List.of(),0));assertThat(r.readyForPartialFailure()).isTrue();assertThat(r.readyForSuccess()).isFalse();assertThat(r.nextAttempts()).isEmpty();
    }
    @Test void fourthAttemptSkippedOutcomeForeignRunAndManifestTargetAreRejected() throws Exception {
        var a=event(start(1,1));
        var fourth=replace(a,"attemptNo",4);fourth=new LedgerEvent(ObservationEventIdsV1.orderStarted(request.requestId(),1,4),fourth.recordDigest(),fourth.record());
        var finished=event(end(start(1,1),NOT_FOUND));
        for(var e:List.of(fourth,replace(a,"runId",UUID.randomUUID()),replace(a,"targetOrderId",99L),replace(finished,"outcome","SKIPPED_UNCHANGED")))
            assertThatThrownBy(()->reducer.reduce(snapshot(List.of(a,e),List.of(),0))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
    }
    @Test void changedStartedReferenceEpochOwnerAndUnknownPayloadFieldsFailClosed() throws Exception {
        var a=start(1,1);var f=event(end(a,ACQUISITION_FAILED));
        var altered=replace(f,"payloadJson",f.record().payloadJson().replace(a.event().recordDigest(),"cd".repeat(32)));
        assertThatThrownBy(()->reducer.reduce(snapshot(List.of(event(a),altered),List.of(),0))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
        var raw=event(a);var extra=replace(raw,"payloadJson",raw.record().payloadJson().replaceFirst("\\{","{\"unknown\":true,"));
        assertThatThrownBy(()->reducer.reduce(snapshot(List.of(extra),List.of(),0))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
        var other=ObservationOrderCommands.started(request,10,1,1,UUID.randomUUID(),request.requester(),time);
        assertThatThrownBy(()->reduce(a,end(a,NOT_FOUND),other)).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
        var high=ObservationOrderCommands.started(request,1,1,2,owner,request.requester(),time);
        var highEnd=ObservationOrderCommands.finished(high,2,owner,request.requester(),time,ACQUISITION_FAILED,null,new Failure(Stage.ACQUISITION,Code.READ_FAILED),null);
        assertThatThrownBy(()->reduce(high,highEnd,start(1,2))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
    }
    @Test void mismatchedAssessmentIdentityAndUnreferencedAssessmentAreRejected() {
        var a=start(1,1);var ref=new ObservationEventFactory.AssessmentRef(request.requestId(),1,1,7,"ab".repeat(32));
        var done=ObservationOrderCommands.finished(a,1,owner,request.requester(),time,CLASSIFIED,ref,null,null);
        for(var verified:List.of(new VerifiedAssessment(UUID.randomUUID(),1,1,7,"ab".repeat(32),Disposition.SELECTED),new VerifiedAssessment(request.requestId(),10,1,7,"ab".repeat(32),Disposition.SELECTED),new VerifiedAssessment(request.requestId(),1,2,7,"ab".repeat(32),Disposition.SELECTED),new VerifiedAssessment(request.requestId(),1,1,8,"ab".repeat(32),Disposition.SELECTED)))
            assertThatThrownBy(()->reducer.reduce(snapshot(List.of(event(a),event(done)),List.of(verified),0))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
        var verified=new VerifiedAssessment(request.requestId(),1,1,7,"ab".repeat(32),Disposition.NORMAL_NULL);
        assertThatThrownBy(()->reducer.reduce(snapshot(List.of(),List.of(verified),0))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
    }
    @Test void configurableSizeAndCountLimitsRejectInsteadOfTruncating() {
        var a=start(1,1);var input=snapshot(List.of(event(a),event(end(a,NOT_FOUND))),List.of(),0);
        for(var limits:List.of(new Limits(1,64L*1024*1024),new Limits(6000,1),new Limits(6000,new ObservationManifestCodecV1().encode(input.manifest()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+100))) {
            var restricted=new ObservationLedgerSnapshot(input.runId(),input.manifest(),input.manifestDigest(),input.events(),input.assessments(),0,limits);
            assertThatThrownBy(()->reducer.reduce(restricted)).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
        }
    }
    @Test void largeProfileManifestIsVerifiedOnceAndStillHasExactResults() {
        var m=request.manifest();var manifest=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),Map.of("approved-profile","x".repeat(700_000)),m.timeOrigins(),m.policy());
        var run=new ObservationRunRequest(request.requestId(),request.executionType(),manifest,request.requester());
        var factory=new ObservationEventFactory(run);var digest=ObservationRunDigestsV1.manifestDigest(manifest);var rows=new ArrayList<LedgerEvent>();
        for(long id:manifest.targetOrderIds()) {
            var sc=new ObservationOrderContextV1(1,digest,manifest.classificationContractVersion(),null,null,1,1,owner,null,null,null);
            var start=factory.withOrderContext(factory.orderStarted(id,1,1,run.requester(),time),sc);
            var fc=new ObservationOrderContextV1(1,digest,manifest.classificationContractVersion(),start.eventUuid(),start.recordDigest(),1,1,owner,null,null,null);
            var done=factory.withOrderContext(factory.orderFinished(id,1,1,run.requester(),time,NOT_FOUND,null),fc);
            rows.add(new LedgerEvent(start.eventUuid(),start.recordDigest(),start.record()));rows.add(new LedgerEvent(done.eventUuid(),done.recordDigest(),done.record()));
        }
        var result=reducer.reduce(new ObservationLedgerSnapshot(run.requestId(),manifest,digest,rows,List.of(),0,Limits.defaults()));
        assertThat(result.counters()).isEqualTo(new ObservationLedgerCounters(2,0,0,0,0,0,0,0,0,2,0));assertThat(result.readyForSuccess()).isTrue();
    }
    @Test void thousandOrdersAndSixThousandEventsRemainDeterministic() {
        var m=request.manifest();var targets=java.util.stream.LongStream.rangeClosed(1,1000).boxed().toList();
        var manifest=new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.range(0,2000),targets,m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),Map.of(),Map.of(),m.policy());
        var run=new ObservationRunRequest(request.requestId(),request.executionType(),manifest,request.requester());var rows=new ArrayList<LedgerEvent>();
        var factory=new ObservationEventFactory(run);String digest=ObservationRunDigestsV1.manifestDigest(manifest);
        // Build a maximum-sized fixture without repeatedly encoding the entire manifest per row.
        for(long id:targets)for(int attempt=1;attempt<=3;attempt++) {
            var sc=new ObservationOrderContextV1(1,digest,manifest.classificationContractVersion(),null,null,1,1,owner,null,null,null);
            var start=factory.withOrderContext(factory.orderStarted(id,attempt,1,run.requester(),time),sc);
            var fc=new ObservationOrderContextV1(1,digest,manifest.classificationContractVersion(),start.eventUuid(),start.recordDigest(),1,1,owner,null,new Failure(Stage.PERSISTENCE,Code.STORAGE_FAILED),null);
            var done=factory.withOrderContext(factory.orderFinished(id,attempt,1,run.requester(),time,PERSISTENCE_FAILED,null),fc);
            rows.add(new LedgerEvent(start.eventUuid(),start.recordDigest(),start.record()));rows.add(new LedgerEvent(done.eventUuid(),done.recordDigest(),done.record()));
        }
        var r=reducer.reduce(new ObservationLedgerSnapshot(run.requestId(),manifest,ObservationRunDigestsV1.manifestDigest(manifest),rows,List.of(),0,Limits.defaults()));
        assertThat(r.counters()).isEqualTo(new ObservationLedgerCounters(1000,0,0,0,0,3000,0,2000,1000,0,0));assertThat(r.cursor()).isEqualTo(2000);assertThat(r.readyForPartialFailure()).isTrue();
        rows.add(rows.getFirst());
        assertThatThrownBy(()->reducer.reduce(new ObservationLedgerSnapshot(run.requestId(),manifest,ObservationRunDigestsV1.manifestDigest(manifest),rows,List.of(),0,Limits.defaults()))).isInstanceOf(ObservationEventLedgerReducer.IntegrityException.class);
    }

}
