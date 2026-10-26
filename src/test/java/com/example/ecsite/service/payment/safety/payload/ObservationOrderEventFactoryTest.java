package com.example.ecsite.service.payment.safety.payload;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.payment.safety.payload.ObservationEventFactory.Outcome.*;
import static com.example.ecsite.service.payment.safety.payload.ObservationOrderContextV1.*;

class ObservationOrderEventFactoryTest {
    final ObservationRunRequest request=ObservationRunContractsV1Test.request();
    final UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000009");
    final Instant time=Instant.parse("2026-10-20T00:00:00.123456Z");
    ObservationOrderCommands.Command start(){return ObservationOrderCommands.started(request,1,1,1,owner,request.requester(),time);}
    @Test void uuidDoesNotDependOnTimeActorOwnerOrPayloadButDigestDoes() {
        var first=start().event();
        for(var other:List.of(
            ObservationOrderCommands.started(request,1,1,1,UUID.randomUUID(),request.requester(),time),
            ObservationOrderCommands.started(request,1,1,1,owner,ObservationRunRequest.Actor.system("別の監査者"),time),
            ObservationOrderCommands.started(request,1,1,1,owner,request.requester(),time.plusNanos(1000)))) {
            assertThat(other.event().eventUuid()).isEqualTo(first.eventUuid());assertThat(other.event().recordDigest()).isNotEqualTo(first.recordDigest());
        }
        var finish=ObservationOrderCommands.finished(start(),1,owner,request.requester(),time,NOT_FOUND,null,null,null).event();
        assertThat(finish.eventUuid()).isEqualTo(ObservationEventIdsV1.orderFinished(request.requestId(),1,1));
        assertThatThrownBy(()->AuditEventDigestV1.verify(finish.record(),first.recordDigest())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void allOutcomeRecordsHaveV56CompatibleNullShape() throws Exception {
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/V56__expand_payment_safety_audit.sql"));
        assertThat(ddl).contains("uq_ps_events_order_attempt","uq_ps_events_order_finished","uq_ps_events_order_accepted","outcome='CLASSIFIED' AND assessment_id IS NOT NULL AND order_id IS NOT NULL AND order_id=target_order_id");
        assertThat(Arrays.stream(ObservationEventFactory.Outcome.values()).map(Enum::name)).doesNotContain("SKIPPED_UNCHANGED");
        for(var outcome:ObservationEventFactory.Outcome.values()) {
            UUID savingOwner=owner;long epoch=1;TakeoverRef ref=null;
            if(outcome==INTERRUPTED){savingOwner=UUID.randomUUID();epoch=2;ref=TakeoverRef.from(request,ObservationLifecycleCommands.prepare(request,ObservationLifecycleCommands.Action.TAKEOVER,UUID.randomUUID(),owner,savingOwner,1,0,request.requester(),time));}
            Failure failure=switch(outcome){case ACQUISITION_FAILED->new Failure(Stage.ACQUISITION,Code.MAPPING_FAILED);case ASSEMBLY_FAILED->new Failure(Stage.ASSEMBLY,Code.UNREPRESENTABLE_INPUT);case PERSISTENCE_FAILED->new Failure(Stage.PERSISTENCE,Code.CODEC_FAILED);case INTERRUPTED->new Failure(Stage.RECOVERY,Code.WORKER_INTERRUPTED);default->null;};
            var assessment=outcome==CLASSIFIED?new ObservationEventFactory.AssessmentRef(request.requestId(),1,1,3,"ab".repeat(32)):null;
            var event=ObservationOrderCommands.finished(start(),epoch,savingOwner,request.requester(),time,outcome,assessment,failure,ref).event();
            var r=event.record();assertThat(r.eventScope()).isEqualTo("RUN");assertThat(r.caseId()).isNull();assertThat(r.caseVersionBefore()).isNull();assertThat(r.caseVersionAfter()).isNull();assertThat(r.fromStatus()).isNull();assertThat(r.toStatus()).isNull();
            assertThat(r.targetOrderId()).isEqualTo(1);assertThat(r.attemptNo()).isEqualTo(1);assertThat(r.leaseEpoch()).isPositive();
            if(outcome==CLASSIFIED){assertThat(r.orderId()).isEqualTo(1);assertThat(r.assessmentId()).isEqualTo(3);assertThat(r.payloadJson()).contains("assessmentDigest","assessmentRecordDigest");}
            else{assertThat(r.orderId()).isNull();assertThat(r.assessmentId()).isNull();}
            AuditEventDigestV1.verify(r,event.recordDigest());
            assertThat(r.payloadJson()).doesNotContain("client_secret","requestBody","input_payload");
        }
    }
    @Test void contextRejectsMissingHashesAndTakeoverIdentity() {
        for(String hash:Arrays.asList(null,"","A".repeat(64),"ab")) {
            assertThatThrownBy(()->new ObservationOrderContextV1(1,hash,"v1",null,null,1,1,owner,null,null,null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->new ObservationOrderContextV1(1,"ab".repeat(32),"v1",UUID.randomUUID(),hash,1,1,owner,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->new TakeoverRef(request.requestId(),UUID.randomUUID(),"ab".repeat(32),"cd".repeat(32),1,2,owner,owner)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TakeoverRef(request.requestId(),UUID.randomUUID(),"ab".repeat(32),"cd".repeat(32),Long.MAX_VALUE,Long.MIN_VALUE,owner,owner)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void factoryRejectsMismatchedStartedReferenceEvenInsidePackage() {
        var s=start();var c=new ObservationOrderContextV1(1,s.context().manifestDigest(),s.context().contractVersion(),UUID.randomUUID(),s.event().recordDigest(),1,1,owner,null,null,null);
        var factory=new ObservationEventFactory(request);
        var base=factory.orderFinished(1,1,1,request.requester(),time,NOT_FOUND,null);
        assertThatThrownBy(()->factory.withOrderContext(base,c)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oversizedManifestCannotBeHiddenByOnlyStoringItsDigest() {
        var m=request.manifest();
        var large=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),Map.of("profile","x".repeat(1024*1024)),m.timeOrigins(),m.policy());
        var r=new ObservationRunRequest(request.requestId(),request.executionType(),large,request.requester());
        assertThatThrownBy(()->ObservationOrderCommands.started(r,1,1,1,owner,r.requester(),time)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void packageAttachmentRejectsContradictoryFinishedContext() {
        var a=start();var factory=new ObservationEventFactory(request);var base=factory.orderFinished(1,1,1,request.requester(),time,NOT_FOUND,null);
        for(var c:List.of(
            new ObservationOrderContextV1(1,a.context().manifestDigest(),a.context().contractVersion(),a.event().eventUuid(),a.event().recordDigest(),2,1,owner,null,null,null),
            new ObservationOrderContextV1(1,a.context().manifestDigest(),a.context().contractVersion(),a.event().eventUuid(),a.event().recordDigest(),1,1,owner,null,new Failure(Stage.CLASSIFICATION,Code.CLASSIFICATION_FAILED),null),
            new ObservationOrderContextV1(1,a.context().manifestDigest(),a.context().contractVersion(),a.event().eventUuid(),a.event().recordDigest(),1,1,owner,null,null,"ab".repeat(32))))
            assertThatThrownBy(()->factory.withOrderContext(base,c)).isInstanceOf(IllegalArgumentException.class);
        var classified=factory.orderFinished(1,1,1,request.requester(),time,CLASSIFIED,new ObservationEventFactory.AssessmentRef(request.requestId(),1,1,7,"ab".repeat(32)));
        var wrong=new ObservationOrderContextV1(1,a.context().manifestDigest(),a.context().contractVersion(),a.event().eventUuid(),a.event().recordDigest(),1,1,owner,null,null,"cd".repeat(32));
        assertThatThrownBy(()->factory.withOrderContext(classified,wrong)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void allOrderFactoryPathsRejectFourthAttemptEvenWithLegacyHigherPolicy() {
        var m=request.manifest();var p=new ObservationRunRequest.Policy(120,20,100,1000,10,30,1000);
        var legacy=new ObservationRunRequest(request.requestId(),request.executionType(),new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),m.verificationProfiles(),m.timeOrigins(),p),request.requester());
        assertThatThrownBy(()->new ObservationEventFactory(legacy).orderStarted(1,4,1,legacy.requester(),time)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void changedErrorCodeChangesDigestWithoutChangingSlotAndAssessmentCannotLeak() {
        var a=ObservationOrderCommands.finished(start(),1,owner,request.requester(),time,ACQUISITION_FAILED,null,new Failure(Stage.ACQUISITION,Code.READ_FAILED),null).event();
        var b=ObservationOrderCommands.finished(start(),1,owner,request.requester(),time,ACQUISITION_FAILED,null,new Failure(Stage.ACQUISITION,Code.MAPPING_FAILED),null).event();
        assertThat(a.eventUuid()).isEqualTo(b.eventUuid());assertThat(a.recordDigest()).isNotEqualTo(b.recordDigest());
        var ref=new ObservationEventFactory.AssessmentRef(request.requestId(),1,1,7,"ab".repeat(32));
        assertThatThrownBy(()->ObservationOrderCommands.finished(start(),1,owner,request.requester(),time,NOT_FOUND,ref,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void declaredErrorCodesAreClosedAndStageBound() {
        for(Code code:Code.values()) {
            int accepted=0;for(Stage stage:Stage.values()) {
                try{new Failure(stage,code);accepted++;}catch(IllegalArgumentException expected){/* incompatible pair */}
            }assertThat(accepted).isEqualTo(1);
        }
        assertThatThrownBy(()->Code.valueOf("RAW_PROVIDER_MESSAGE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationEventFactory.Outcome.valueOf("SKIPPED_UNCHANGED")).isInstanceOf(IllegalArgumentException.class);
    }
}
