package com.example.ecsite.service.payment.safety.payload;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.payment.safety.payload.ObservationEventFactory.Outcome.*;
import static com.example.ecsite.service.payment.safety.payload.ObservationOrderContextV1.*;

class ObservationOrderCommandsTest {
    final ObservationRunRequest request=ObservationRunContractsV1Test.request();
    final UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000009");
    final Instant time=Instant.parse("2026-10-20T00:00:00.123456Z");
    ObservationOrderCommands.Command start(){return ObservationOrderCommands.started(request,1,1,1,owner,request.requester(),time);}
    @Test void startedIsTypedStableAndContainsNoSelfDigestCycle() {
        var a=start();var b=start();
        assertThat(a.event().eventUuid()).isEqualTo(ObservationEventIdsV1.orderStarted(request.requestId(),1,1));
        assertThat(a.event().recordDigest()).isEqualTo(b.event().recordDigest());
        assertThat(a.context().startedDigest()).isNull();
        assertThat(a.context().saveOwner()).isEqualTo(owner);
        assertThat(a.event().record().payloadJson()).contains("orderContext","contractVersion","manifestDigest");
    }
    @Test void everyNormalOutcomeRequiresCorrectTypedDetails() {
        for(var outcome:List.of(CLASSIFIED,NOT_FOUND,ACQUISITION_FAILED,ASSEMBLY_FAILED,PERSISTENCE_FAILED)) {
            var assessment=outcome==CLASSIFIED?new ObservationEventFactory.AssessmentRef(request.requestId(),1,1,7,"ab".repeat(32)):null;
            Failure error=switch(outcome){case ACQUISITION_FAILED->new Failure(Stage.ACQUISITION,Code.READ_FAILED);case ASSEMBLY_FAILED->new Failure(Stage.CLASSIFICATION,Code.CLASSIFICATION_FAILED);case PERSISTENCE_FAILED->new Failure(Stage.PERSISTENCE,Code.STORAGE_FAILED);default->null;};
            var c=ObservationOrderCommands.finished(start(),1,owner,request.requester(),time,outcome,assessment,error,null);
            assertThat(c.context().startedDigest()).isEqualTo(start().event().recordDigest());
            assertThat(c.event().record().outcome()).isEqualTo(outcome.name());
            assertThat(c.event().record().errorStage()).isEqualTo(error==null?null:error.stage().name());
            AuditEventDigestV1.verify(c.event().record(),c.event().recordDigest());
        }
    }
    @Test void interruptRequiresARealMatchingTypedTakeoverEnvelope() {
        UUID next=UUID.randomUUID();
        var takeover=ObservationLifecycleCommands.prepare(request,ObservationLifecycleCommands.Action.TAKEOVER,UUID.randomUUID(),owner,next,1,0,request.requester(),time);
        var ref=TakeoverRef.from(request,takeover);
        var c=ObservationOrderCommands.finished(start(),2,next,request.requester(),time,INTERRUPTED,null,new Failure(Stage.RECOVERY,Code.WORKER_INTERRUPTED),ref);
        assertThat(c.context().takeover().eventUuid()).isEqualTo(takeover.event().orElseThrow().eventUuid());
        assertThatThrownBy(()->ObservationOrderCommands.finished(start(),1,owner,request.requester(),time,INTERRUPTED,null,new Failure(Stage.RECOVERY,Code.WORKER_INTERRUPTED),ref)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->TakeoverRef.from(request,ObservationLifecycleCommands.create(request,owner,time))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void strictIdentityTimeAndActorValidation() {
        for(int attempt:new int[]{0,4})assertThatThrownBy(()->ObservationOrderCommands.started(request,1,attempt,1,owner,request.requester(),time)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationOrderCommands.started(request,0,1,1,owner,request.requester(),time)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationOrderCommands.started(request,1,1,0,owner,request.requester(),time)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationOrderCommands.started(request,1,1,1,null,request.requester(),time)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationOrderCommands.started(request,1,1,1,owner,request.requester(),time.plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        for(String bad:List.of("\u0000","\ud800","x".repeat(101)))assertThatThrownBy(()->ObservationRunRequest.Actor.system(bad)).isInstanceOf(IllegalArgumentException.class);
        var unicode=ObservationRunRequest.Actor.system("監査\uD83D\uDD0D");
        assertThat(ObservationOrderCommands.started(request,1,1,1,owner,unicode,time).event().record().actorUsername()).isEqualTo(unicode.username());
    }
    @Test void rejectsWrongOutcomeDetailsEpochOwnerAndStage() {
        var s=start();var error=new Failure(Stage.ACQUISITION,Code.READ_FAILED);
        assertThatThrownBy(()->new Failure(Stage.ASSEMBLY,Code.READ_FAILED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationOrderCommands.finished(s,2,owner,request.requester(),time,NOT_FOUND,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationOrderCommands.finished(s,1,UUID.randomUUID(),request.requester(),time,NOT_FOUND,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationOrderCommands.finished(s,1,owner,request.requester(),time,CLASSIFIED,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationOrderCommands.finished(s,1,owner,request.requester(),time,NOT_FOUND,null,error,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationOrderCommands.finished(s,1,owner,request.requester(),time,ASSEMBLY_FAILED,null,error,null)).isInstanceOf(IllegalArgumentException.class);
        var foreign=new ObservationEventFactory.AssessmentRef(UUID.randomUUID(),1,1,7,"ab".repeat(32));
        assertThatThrownBy(()->ObservationOrderCommands.finished(s,1,owner,request.requester(),time,CLASSIFIED,foreign,null,null)).isInstanceOf(IllegalArgumentException.class);
        var finished=ObservationOrderCommands.finished(s,1,owner,request.requester(),time,NOT_FOUND,null,null,null);
        assertThatThrownBy(()->ObservationOrderCommands.finished(finished,1,owner,request.requester(),time,NOT_FOUND,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
}
