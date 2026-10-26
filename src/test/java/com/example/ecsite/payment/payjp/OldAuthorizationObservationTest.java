package com.example.ecsite.payment.payjp;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.example.ecsite.config.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import tools.jackson.databind.json.JsonMapper;

class OldAuthorizationObservationTest {
    static final UUID OP=UUID.fromString("12345678-1234-1234-1234-123456789abc");
    static final CaptureConnectionIdentity CONNECTION=new CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp");
    final AtomicLong nanos=new AtomicLong(100);
    final Instant now=Instant.parse("2026-10-23T00:00:00Z");
    Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    RestClient.Builder builder;MockRestServiceServer server;PayJpPaymentGateway gateway;
    @BeforeEach void setup(){
        builder=RestClient.builder().baseUrl("https://api.pay.jp");server=MockRestServiceServer.bindTo(builder).build();
        gateway=new PayJpPaymentGateway(builder.build(),new PayJpAuthorizationMapper(),
            new PayJpProperties("pk_test_dummy","sk_test_dummy","https://api.pay.jp","dummy"),
            new CaptureConnectionProperties("test","account","TEST","https://api.pay.jp"),clock,nanos::get);
    }
    OldAuthorizationObservation.Expected expected(){return new OldAuthorizationObservation.Expected(1,OP,8,"pfw_old",910,CONNECTION,4,"snapshot");}
    String body(){return """
        {"id":"pfw_old","status":"requires_capture","amount":910,"currency":"jpy","capture_method":"manual",
         "livemode":false,"metadata":{"order_id":"1","order_change_operation_id":"12345678-1234-1234-1234-123456789abc"},
         "amount_capturable":910,"amount_received":null,"client_secret":"sensitive-never-retained"}
        """;}
    void response(String body){server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old"))
        .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));}
    @Test void rawGetCreatesBoundFreshSingleUseEvidence(){
        var expected=expected();response(body());var observation=gateway.observeOldAuthorization(expected);
        observation.requireMatches(expected);observation.requireFresh();
        assertThat(observation.startedAt()).isEqualTo(now);assertThat(observation.completedAt()).isEqualTo(now);
        assertThat(observation.toString()).doesNotContain("sensitive", "pfw_old");
        observation.consumeFor(expected);assertThatThrownBy(()->observation.consumeFor(expected)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalid(){
        var values=new ArrayList<org.junit.jupiter.params.provider.Arguments>();
        Map<String,List<String>> fields=Map.of(
            "id",List.of("\"pfw_other\"","null","1"),"status",List.of("\"canceled\"","\"processing\"","\"succeeded\"","\"requires_payment_method\"","\"requires_confirmation\"","\"requires_action\"","\"future\"","null"),
            "amount",List.of("909","null","\"910\"","910.0","true","[]","{}","9223372036854775808"),
            "currency",List.of("\"JPY\"","null","1"),"capture_method",List.of("\"automatic\"","null"),
            "livemode",List.of("true","null","\"false\""),
            "amount_capturable",List.of("null","909","911","\"910\"","910.0","true","[]","{}","9223372036854775808"),
            "amount_received",List.of("1","-1","\"0\"","0.0","true","[]","{}","9223372036854775808"));
        fields.forEach((field,list)->{values.add(org.junit.jupiter.params.provider.Arguments.of(field,"MISSING"));list.forEach(v->values.add(org.junit.jupiter.params.provider.Arguments.of(field,v)));});
        return values.stream();
    }
    @ParameterizedTest @MethodSource("invalid") void rejectsRawMismatch(String field,String replacement){
        var json=JsonMapper.builder().build().readTree(body()).deepCopy();
        var object=(tools.jackson.databind.node.ObjectNode)json;
        if("MISSING".equals(replacement))object.remove(field);else object.set(field,JsonMapper.builder().build().readTree(replacement));
        response(object.toString());assertThatThrownBy(()->gateway.observeOldAuthorization(expected())).isInstanceOf(RuntimeException.class);server.verify();
    }
    @Test void integerZeroAlsoAccepted(){response(body().replace("\"amount_received\":null","\"amount_received\":0"));gateway.observeOldAuthorization(expected()).requireFresh();server.verify();}
    @Test void monotonicExpiryCannotBeUndoneByWallClock(){response(body());var value=gateway.observeOldAuthorization(expected());nanos.addAndGet(5_000_000_000L);value.requireFresh();nanos.incrementAndGet();assertThatThrownBy(value::requireFresh).isInstanceOf(IllegalStateException.class);}
    @Test void negativeElapsedRejected(){response(body());var value=gateway.observeOldAuthorization(expected());nanos.set(99);assertThatThrownBy(value::requireFresh).isInstanceOf(IllegalStateException.class);}
    @Test void everyBindingRejectsSubstitution(){
        response(body());var e=expected();var value=gateway.observeOldAuthorization(e);
        var variants=List.of(
            new OldAuthorizationObservation.Expected(2,OP,8,"pfw_old",910,CONNECTION,4,"snapshot"),
            new OldAuthorizationObservation.Expected(1,UUID.randomUUID(),8,"pfw_old",910,CONNECTION,4,"snapshot"),
            new OldAuthorizationObservation.Expected(1,OP,9,"pfw_old",910,CONNECTION,4,"snapshot"),
            new OldAuthorizationObservation.Expected(1,OP,8,"other",910,CONNECTION,4,"snapshot"),
            new OldAuthorizationObservation.Expected(1,OP,8,"pfw_old",911,CONNECTION,4,"snapshot"),
            new OldAuthorizationObservation.Expected(1,OP,8,"pfw_old",910,new CaptureConnectionIdentity("other","account","TEST","https://api.pay.jp"),4,"snapshot"),
            new OldAuthorizationObservation.Expected(1,OP,8,"pfw_old",910,CONNECTION,5,"snapshot"),
            new OldAuthorizationObservation.Expected(1,OP,8,"pfw_old",910,CONNECTION,4,"changed"));
        for(var other:variants)assertThatThrownBy(()->value.requireMatches(other)).isInstanceOf(IllegalStateException.class);
        value.requireTarget(1,OP);assertThatThrownBy(()->value.requireTarget(2,OP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->value.requireConsumedFor(e)).isInstanceOf(IllegalStateException.class);
        assertThat(value.consumeBound()).isEqualTo(e);value.requireConsumedFor(e);
        assertThatThrownBy(value::consumeBound).isInstanceOf(IllegalStateException.class);
        assertThat(java.io.Serializable.class.isAssignableFrom(value.getClass())).isFalse();
        assertThat(Arrays.stream(value.getClass().getDeclaredConstructors()).allMatch(c->java.lang.reflect.Modifier.isPrivate(c.getModifiers()))).isTrue();
    }
    @Test void connectionMismatchPreventsEvenGet(){
        var other=new OldAuthorizationObservation.Expected(1,OP,8,"pfw_old",910,
            new CaptureConnectionIdentity("other","account","TEST","https://api.pay.jp"),4,"snapshot");
        assertThatThrownBy(()->gateway.observeOldAuthorization(other)).isInstanceOf(IllegalStateException.class);server.verify();
    }
    @Test void slowGetAndBackwardsCompletionRejected(){
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(request->{
            nanos.addAndGet(5_000_000_001L);return withSuccess(body(),MediaType.APPLICATION_JSON).createResponse(request);
        });
        assertThatThrownBy(()->gateway.observeOldAuthorization(expected())).isInstanceOf(RuntimeException.class);server.verify();
    }
    @Test void backwardsWallClockDuringGetRejected(){
        var wall=new java.util.concurrent.atomic.AtomicReference<>(now);
        clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return wall.get();}};
        setup();
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(request->{
            wall.set(now.minusSeconds(1));return withSuccess(body(),MediaType.APPLICATION_JSON).createResponse(request);
        });
        assertThatThrownBy(()->gateway.observeOldAuthorization(expected())).isInstanceOf(RuntimeException.class);
    }
    @Test void backwardsWallClockAfterObservationRejected(){
        var wall=new java.util.concurrent.atomic.AtomicReference<>(now);
        clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return wall.get();}};
        setup();response(body());var value=gateway.observeOldAuthorization(expected());wall.set(now.minusSeconds(1));
        assertThatThrownBy(value::requireFresh).isInstanceOf(IllegalStateException.class);
    }
    @Test void concurrentConsumptionHasOnlyOneWinner() throws Exception{
        response(body());var value=gateway.observeOldAuthorization(expected());var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var gate=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.Callable<Boolean> call=()->{gate.await();try{value.consumeFor(expected());return true;}catch(IllegalStateException rejected){return false;}};
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();assertThat((a.get()?1:0)+(b.get()?1:0)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void failedConsumptionAlsoBurnsObservation(){
        response(body());var value=gateway.observeOldAuthorization(expected());nanos.addAndGet(5_000_000_001L);
        assertThatThrownBy(()->value.consumeFor(expected())).isInstanceOf(IllegalStateException.class);nanos.set(100);
        assertThatThrownBy(()->value.consumeFor(expected())).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={400,404,409,422,429,500})
    void httpFailureDoesNotRetainBodyOrRetry(int status){
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old"))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("sensitive-never-retained").contentType(MediaType.APPLICATION_JSON));
        var failure=catchThrowable(()->gateway.observeOldAuthorization(expected()));assertThat(failure).hasNoCause();
        assertThat(failure.toString()).doesNotContain("sensitive-never-retained");server.verify();
    }
    @Test void futureCompletionRelativeToCurrentClockRejected(){
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return reads.getAndIncrement()==1?now.plusSeconds(1):now;}};
        setup();response(body());assertThatThrownBy(()->gateway.observeOldAuthorization(expected())).isInstanceOf(RuntimeException.class);
    }
    @Test void wrongContextConsumptionIsIrreversible(){
        response(body());var value=gateway.observeOldAuthorization(expected());var e=expected();
        var other=new OldAuthorizationObservation.Expected(99,e.operationId(),e.oldPaymentId(),e.oldFlowId(),e.amount(),e.connection(),e.version(),e.snapshot());
        assertThatThrownBy(()->value.consumeFor(other)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->value.consumeFor(e)).isInstanceOf(IllegalStateException.class);
    }
    @Test void activeDatabaseTransactionPreventsGet(){
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->gateway.observeOldAuthorization(expected())).isInstanceOf(IllegalStateException.class);}
        finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
        server.verify();
    }
    @Test void consumedAttestationExpiresFromGetStart(){
        response(body());var value=gateway.observeOldAuthorization(expected());value.consumeBound();
        nanos.addAndGet(5_000_000_001L);
        assertThatThrownBy(()->value.requireConsumedFor(expected())).isInstanceOf(IllegalStateException.class);
    }
    @Test void oldFlowNeedsNoNewOperationMetadata(){
        var object=(tools.jackson.databind.node.ObjectNode)JsonMapper.builder().build().readTree(body());
        object.remove("metadata");response(object.toString());gateway.observeOldAuthorization(expected()).requireFresh();server.verify();
    }
}
