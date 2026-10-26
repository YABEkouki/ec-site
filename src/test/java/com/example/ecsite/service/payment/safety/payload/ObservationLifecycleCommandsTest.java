package com.example.ecsite.service.payment.safety.payload;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ObservationLifecycleCommandsTest {
    final ObservationRunRequest request=ObservationRunContractsV1Test.request();
    final UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000009");
    final UUID control=UUID.fromString("00000000-0000-0000-0000-000000000008");
    final Instant now=Instant.parse("2026-10-20T00:00:00.123456Z");
    @Test void preparedCreateBindsOwnerAndRetainsIdentityAndTime() {
        var a=ObservationLifecycleCommands.create(request,owner,now);
        var b=ObservationLifecycleCommands.create(request,control,now);
        assertThat(a.event().orElseThrow().eventUuid()).isEqualTo(b.event().orElseThrow().eventUuid());
        assertThat(a.commandDigest()).isNotEqualTo(b.commandDigest());
        assertThat(a.event().orElseThrow().recordDigest()).isNotEqualTo(b.event().orElseThrow().recordDigest());
        assertThat(a.event().orElseThrow().record().payloadJson()).contains("lifecycleContextVersion","ownerBefore","ownerAfter","epochBefore","epochAfter","versionBefore","versionAfter");
        assertThat(a.event().orElseThrow().record().occurredAt()).isEqualTo(now);
        assertThat(a.commandDigest()).isEqualTo(ObservationLifecycleCommands.create(request,owner,now).commandDigest());
    }
    @Test void sameControlUuidBindsCasAndOwnerConditions() {
        var a=ObservationLifecycleCommands.prepare(request,ObservationLifecycleCommands.Action.PAUSE,control,owner,null,1,0,request.requester(),now);
        var b=ObservationLifecycleCommands.prepare(request,ObservationLifecycleCommands.Action.PAUSE,control,owner,null,1,1,request.requester(),now);
        assertThat(a.event().orElseThrow().eventUuid()).isEqualTo(b.event().orElseThrow().eventUuid());
        assertThat(a.event().orElseThrow().recordDigest()).isNotEqualTo(b.event().orElseThrow().recordDigest());
        assertThat(a.commandDigest()).isNotEqualTo(b.commandDigest());
    }
    @Test void heartbeatHasNoEventAndCommandsRejectInvalidShapes() {
        var a=ObservationLifecycleCommands.prepare(request,ObservationLifecycleCommands.Action.HEARTBEAT,control,owner,owner,1,0,request.requester(),now);
        assertThat(a.event()).isEmpty();
        assertThatThrownBy(()->ObservationLifecycleCommands.create(request,owner,now.plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationLifecycleCommands.prepare(request,ObservationLifecycleCommands.Action.RESUME,control,owner,owner,1,0,request.requester(),now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationLifecycleCommands.prepare(request,ObservationLifecycleCommands.Action.TAKEOVER,control,owner,control,Long.MAX_VALUE,0,request.requester(),now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void replayComparesFullBusinessContentIndependentlyOfDigestAndJsonPresentation() {
        var a=CanonicalDigestV1Test.event("{\"version\":1,\"a\":[1,null,1]}");
        var reordered=CanonicalDigestV1Test.event("{ \"a\":[1,null,1],\"version\":1 }");
        var changed=CanonicalDigestV1Test.event("{\"version\":1,\"a\":[null,1,1]}");
        assertThat(AuditEventDigestV1.sameContent(a,reordered)).isTrue();
        assertThat(AuditEventDigestV1.sameContent(a,changed)).isFalse();
        var one=ObservationLifecycleCommands.create(request,owner,now).event().orElseThrow().record();
        var other=ObservationLifecycleCommands.create(request,control,now).event().orElseThrow().record();
        assertThat(AuditEventDigestV1.sameContent(one,other)).isFalse();
    }
    @Test void contextCannotBeAttachedToOrderEventsOrConflictingEpochs() {
        var a=ObservationLifecycleCommands.create(request,owner,now);
        var context=a.context().orElseThrow();var factory=new ObservationEventFactory(request);
        assertThatThrownBy(()->factory.withLifecycleContext(factory.orderStarted(1,1,1,request.requester(),now),context)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.withLifecycleContext(factory.paused(control,1,request.requester(),now),context)).isInstanceOf(IllegalArgumentException.class);
    }
}
