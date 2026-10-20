package com.example.ecsite.service.payment.safety.payload;

import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ObservationEventFactoryTest {
    final ObservationRunRequest request=ObservationRunContractsV1Test.request();
    final ObservationEventFactory factory=new ObservationEventFactory(request);
    final ObservationRunRequest.Actor actor=ObservationRunRequest.Actor.system("worker");
    final Instant time=Instant.parse("2026-10-20T01:02:03.123456Z");
    final UUID control=UUID.fromString("00000000-0000-0000-0000-000000000002");
    ObservationEventFactory.AssessmentRef assessment(){return new ObservationEventFactory.AssessmentRef(request.requestId(),1L,1,30L,"a".repeat(64));}
    @Test void deterministicUuidUsesOnlyBusinessAddressNotTimeOrResult() {
        var a=factory.finished(ObservationEventFactory.TerminalStatus.SUCCESS,2,actor,time);
        var b=factory.runFailed(2,actor,time.plusSeconds(1));
        assertThat(a.eventUuid()).isEqualTo(b.eventUuid()).isEqualTo(ObservationEventIdsV1.runFinished(request.requestId()));
        assertThat(a.recordDigest()).isNotEqualTo(b.recordDigest());
        assertThat(a.record().eventType()).isEqualTo("FINISHED");assertThat(b.record().payloadJson()).contains("\"terminalStatus\":\"FAILED\"");
        assertThat(a.eventUuid().version()).isEqualTo(5);assertThat(a.eventUuid().variant()).isEqualTo(2);
        assertThat(factory.started(time).eventUuid()).isEqualTo(factory.started(time.plusSeconds(1)).eventUuid());
    }
    @Test void everyUuidSlotIsDistinctAndStable() {
        var run=request.requestId();
        var ids=List.of(ObservationEventIdsV1.runStarted(run),ObservationEventIdsV1.runFinished(run),
            ObservationEventIdsV1.orderStarted(run,1,1),ObservationEventIdsV1.orderFinished(run,1,1),
            ObservationEventIdsV1.orderStarted(run,10,1),ObservationEventIdsV1.orderStarted(run,1,2),
            ObservationEventIdsV1.leaseTaken(run,2),ObservationEventIdsV1.leaseTaken(run,3),
            ObservationEventIdsV1.paused(run,control),ObservationEventIdsV1.resumed(run,control));
        assertThat(new HashSet<>(ids)).hasSize(ids.size());
        assertThat(ObservationEventIdsV1.orderFinished(run,1,1)).isEqualTo(ObservationEventIdsV1.orderFinished(run,1,1));
    }
    @Test void v56RunEventShapeIsMatchedForEveryFactoryMethodAndOutcome() {
        var events=new ArrayList<ObservationEventFactory.Event>(List.of(factory.started(time),factory.orderStarted(1,1,1,actor,time),
            factory.paused(control,1,actor,time),factory.resumed(control,2,actor,time),factory.leaseTaken(2,actor,time),
            factory.finished(ObservationEventFactory.TerminalStatus.PARTIAL_FAILURE,2,actor,time),factory.runFailed(2,actor,time)));
        for(var outcome:ObservationEventFactory.Outcome.values())events.add(factory.orderFinished(1,1,1,actor,time,outcome,
            outcome==ObservationEventFactory.Outcome.CLASSIFIED?assessment():null));
        for(var event:events) {
            var r=event.record();assertThat(r.eventScope()).isEqualTo("RUN");
            assertThat(r.caseId()).isNull();assertThat(r.caseVersionBefore()).isNull();assertThat(r.caseVersionAfter()).isNull();
            assertThat(r.fromStatus()).isNull();assertThat(r.toStatus()).isNull();
            assertThat(r.runId()).isEqualTo(request.requestId());assertThat(r.payloadFormatVersion()).isEqualTo((short)1);
            assertThat(r.occurredAt()).isEqualTo(time);assertThat(r.leaseEpoch()).isPositive();
            assertThat(r.payloadJson()).contains("\"version\":1","\"mode\":\"OBSERVE_ONLY\"","NOT_EVALUATED").doesNotContain("requestBody","client_secret","input_payload");
            if(r.eventType().startsWith("ORDER_")) {
                assertThat(r.targetOrderId()).isEqualTo(1);assertThat(r.attemptNo()).isEqualTo(1);
                if(r.eventType().equals("ORDER_FINISHED")) {
                    assertThat(r.outcome()).isNotNull();
                    if("CLASSIFIED".equals(r.outcome())){assertThat(r.orderId()).isEqualTo(r.targetOrderId());assertThat(r.assessmentId()).isPositive();}
                    else {assertThat(r.orderId()).isNull();assertThat(r.assessmentId()).isNull();}
                }else {assertThat(r.outcome()).isNull();assertThat(r.orderId()).isNull();assertThat(r.assessmentId()).isNull();}
            }else {assertThat(r.targetOrderId()).isNull();assertThat(r.attemptNo()).isNull();assertThat(r.orderId()).isNull();assertThat(r.assessmentId()).isNull();assertThat(r.outcome()).isNull();}
            event.verify(event.eventUuid(),event.recordDigest());
            AuditEventDigestV1.verify(r,event.recordDigest());
        }
    }
    @ParameterizedTest @ValueSource(longs={0,-1})
    void rejectsInvalidEpochAndTarget(long value) {
        assertThatThrownBy(()->factory.orderStarted(1,1,value,actor,time)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.orderStarted(value,1,1,actor,time)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void refusesUnknownTargetAttemptsAssessmentMismatchAndMissingControl() {
        assertThatThrownBy(()->factory.orderStarted(2,1,1,actor,time)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.orderStarted(1,0,1,actor,time)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.orderStarted(1,4,1,actor,time)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.orderFinished(1,1,1,actor,time,ObservationEventFactory.Outcome.CLASSIFIED,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.orderFinished(1,1,1,actor,time,ObservationEventFactory.Outcome.NOT_FOUND,assessment())).isInstanceOf(IllegalArgumentException.class);
        var wrong=new ObservationEventFactory.AssessmentRef(UUID.fromString("00000000-0000-0000-0000-000000000009"),1,1,30,"a".repeat(64));
        assertThatThrownBy(()->factory.orderFinished(1,1,1,actor,time,ObservationEventFactory.Outcome.CLASSIFIED,wrong)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.paused(null,1,actor,time)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.resumed(control,1,actor,time)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.leaseTaken(1,actor,time)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsSubmicrosecondPrecisionAndVerifiesIdentitySeparatelyFromDigest() {
        assertThatThrownBy(()->factory.started(time.plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        var event=factory.started(time);event.verify(event.eventUuid(),event.recordDigest());
        assertThatThrownBy(()->event.verify(event.eventUuid(),"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->event.verify(control,event.recordDigest())).isInstanceOf(IllegalArgumentException.class);
        assertThat(factory.started(time).record()).isEqualTo(factory.started(time).record());
    }
    @Test void staticV56CheckContractIsPinned() throws Exception {
        byte[] bytes;try(var in=getClass().getResourceAsStream("/db/migration/V56__expand_payment_safety_audit.sql")){bytes=Objects.requireNonNull(in).readAllBytes();}
        String sql=new String(bytes,StandardCharsets.UTF_8);
        assertThat(sql).contains("event_type IN ('STARTED','PAUSED','RESUMED','LEASE_TAKEN','FINISHED')",
            "event_type IN ('ORDER_STARTED','ORDER_FINISHED')","record_digest VARCHAR(64)","occurred_at TIMESTAMPTZ NOT NULL",
            "WHERE event_scope='RUN' AND event_type='ORDER_FINISHED'", "outcome='CLASSIFIED' AND assessment_id IS NOT NULL AND order_id IS NOT NULL AND order_id=target_order_id");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo("5eae0cefdda4deaaeb503a6d63b05e71693dac968001eeb65ec123ed149343c5");
    }
    @Test void goldenContracts() {
        org.assertj.core.api.SoftAssertions.assertSoftly(soft->{
            soft.assertThat(ObservationRunDigestsV1.manifestDigest(request.manifest())).isEqualTo("4a9c98193cb8eca6ef6d0538c06481c3bb45feb2ab97de7658b5b588ae3d3fa3");
            soft.assertThat(ObservationRunDigestsV1.requestDigest(request)).isEqualTo("48981b38fb6e71ac53e03f587899fea8192c50fbbd23daac068cc8560ded4e93");
            soft.assertThat(ObservationEventIdsV1.runStarted(request.requestId()).toString()).isEqualTo("3174e744-cf67-566b-9f14-fa8c06b24110");
            soft.assertThat(factory.started(time).recordDigest()).isEqualTo("13bf0b387b15c4290889080c0a0ce0d5c7d40477aad523e36b203b4b1bf6ea81");
        });
    }
    @Test void remainingUuidGoldenSlotsMatchStandardUuidV5() {
        var run=request.requestId();
        assertThat(ObservationEventIdsV1.orderFinished(run,1,2).toString()).isEqualTo("f3021a09-3fc1-5b5b-8e9d-a136beee7804");
        assertThat(ObservationEventIdsV1.leaseTaken(run,3).toString()).isEqualTo("360c1a5a-9843-530a-b787-5e71115a4302");
        assertThat(ObservationEventIdsV1.paused(run,control).toString()).isEqualTo("9a1a2bcc-b5b1-596c-aeb3-cb2177ab26ff");
    }
    @Test void assessmentMustMatchOrderAndAttemptNotJustRun() {
        var wrongOrder=new ObservationEventFactory.AssessmentRef(request.requestId(),10,1,30,"a".repeat(64));
        var wrongAttempt=new ObservationEventFactory.AssessmentRef(request.requestId(),1,2,30,"a".repeat(64));
        for(var ref:List.of(wrongOrder,wrongAttempt))
            assertThatThrownBy(()->factory.orderFinished(1,1,1,actor,time,ObservationEventFactory.Outcome.CLASSIFIED,ref)).isInstanceOf(IllegalArgumentException.class);
    }
}
