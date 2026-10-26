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
import com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence;
import tools.jackson.databind.json.JsonMapper;

class NewFlowClosureObservationTest {
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
    com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected expected(){return com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(8,4,
        new NewFlowTerminationEvidence.Expected(1,OP,"pfw_new",910,CONNECTION),
        new NewFlowClosureObservation.AbortIntent(OP,LocalDateTime.of(2026,10,23,9,0),"CUSTOMER","CUSTOMER_REQUEST",3),
        new NewFlowClosureObservation.RequestBinding(9,"00".repeat(32)),Optional.empty(),
        new NewFlowClosureObservation.FinancialSnapshot("payment",List.of("authorization"),"ABSENT"));}
    String body(){return """
        {"id":"pfw_new","status":"canceled","amount":910,"currency":"jpy","capture_method":"manual",
         "livemode":false,"metadata":{"order_id":"1","order_change_operation_id":"12345678-1234-1234-1234-123456789abc"},
         "amount_capturable":0,"amount_received":0,"client_secret":"sensitive-never-retained"}
        """;}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"requires_capture,NOT_CLOSED","processing,RETRY_LATER",
        "requires_payment_method,RETRY_LATER","requires_confirmation,RETRY_LATER","requires_action,RETRY_LATER",
        "succeeded,MANUAL_REVIEW_REQUIRED","future_status,RETRY_LATER"})
    void assessmentNeverMintsClosureForOtherStates(String status,String result){
        response(body().replace("canceled",status));
        var assessed=gateway.assessNewFlowClosure(expected());
        assertThat(assessed.kind().name()).isEqualTo(result);assertThat(assessed.closure()).isEmpty();server.verify();
    }
    @Test void assessmentCanceledUsesExistingStrictSingleUseToken(){
        response(body());var assessed=gateway.assessNewFlowClosure(expected());
        assertThat(assessed.kind().name()).isEqualTo("CLOSED");var token=assessed.closure().orElseThrow();
        token.consumeFor(expected());assertThatThrownBy(()->token.consumeFor(expected())).isInstanceOf(IllegalStateException.class);server.verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"\"pfw_new\"|\"pfw_other\"","\"jpy\"|\"usd\"","910|911",
        "\"manual\"|\"automatic\"","false|true","\"order_id\":\"1\"|\"order_id\":1",
        "\"amount_received\":0|\"amount_received\":null","\"amount_capturable\":0|\"amount_capturable\":null"})
    void assessmentCannotBypassA1(String mutation){
        var parts=mutation.split("\\|",-1);response(body().replace(parts[0],parts[1]));
        assertThatThrownBy(()->gateway.assessNewFlowClosure(expected())).isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);server.verify();
    }
    void response(String body){server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_new"))
        .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));}
    @Test void rawGetCreatesBoundFreshSingleUseEvidence(){
        var expected=expected();response(body());var observation=gateway.observeNewFlowClosure(expected);
        observation.requireMatches(expected);observation.requireFresh();
        assertThat(observation.startedAt()).isEqualTo(now);assertThat(observation.completedAt()).isEqualTo(now);
        assertThat(observation.toString()).doesNotContain("sensitive", "pfw_new");
        observation.consumeFor(expected);assertThatThrownBy(()->observation.consumeFor(expected)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalid(){
        var values=new ArrayList<org.junit.jupiter.params.provider.Arguments>();
        Map<String,List<String>> fields=Map.of(
            "id",List.of("\"pfw_old\"","null","1"),"status",List.of("\"requires_capture\"","\"processing\"","\"succeeded\"","\"requires_payment_method\"","\"requires_confirmation\"","\"requires_action\"","\"future\"","null"),
            "amount",List.of("909","null","\"910\"","910.0","true","[]","{}","9223372036854775808"),
            "currency",List.of("\"JPY\"","null","1"),"capture_method",List.of("\"automatic\"","null"),
            "livemode",List.of("true","null","\"false\""),
            "amount_capturable",List.of("null","1","-1","909","911","\"910\"","910.0","true","[]","{}","9223372036854775808"),
            "amount_received",List.of("null","1","-1","\"0\"","0.0","true","[]","{}","9223372036854775808"),
            "metadata",List.of("null","{}","{\"order_id\":1}","{\"order_id\":\"2\"}","{\"order_id\":\"1\",\"order_change_operation_id\":1}"));
        fields.forEach((field,list)->{values.add(org.junit.jupiter.params.provider.Arguments.of(field,"MISSING"));list.forEach(v->values.add(org.junit.jupiter.params.provider.Arguments.of(field,v)));});
        return values.stream();
    }
    @ParameterizedTest @MethodSource("invalid") void rejectsRawMismatch(String field,String replacement){
        var json=JsonMapper.builder().build().readTree(body()).deepCopy();
        var object=(tools.jackson.databind.node.ObjectNode)json;
        if("MISSING".equals(replacement))object.remove(field);else object.set(field,JsonMapper.builder().build().readTree(replacement));
        response(object.toString());assertThatThrownBy(()->gateway.observeNewFlowClosure(expected())).isInstanceOf(RuntimeException.class);server.verify();
    }
    @Test void monotonicExpiryCannotBeUndoneByWallClock(){response(body());var value=gateway.observeNewFlowClosure(expected());nanos.addAndGet(5_000_000_000L);value.requireFresh();nanos.incrementAndGet();assertThatThrownBy(value::requireFresh).isInstanceOf(IllegalStateException.class);}
    @Test void negativeElapsedRejected(){response(body());var value=gateway.observeNewFlowClosure(expected());nanos.set(99);assertThatThrownBy(value::requireFresh).isInstanceOf(IllegalStateException.class);}
    @Test void everyIdentityBindingAndOptionalRequestAreCompared(){
        response(body());var e=expected();var value=gateway.observeNewFlowClosure(e);
        var changedFlow=new NewFlowTerminationEvidence.Expected(2,OP,"pfw_other",920,CONNECTION);
        var otherConnection=new NewFlowTerminationEvidence.Expected(1,OP,"pfw_new",910,
            new CaptureConnectionIdentity("other","account","TEST","https://api.pay.jp"));
        var variants=List.of(
            com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(99,e.version(),e.flow(),e.intent(),e.create(),e.abort(),e.snapshot()),
            com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(e.paymentId(),e.version(),changedFlow,e.intent(),e.create(),e.abort(),e.snapshot()),
            com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(e.paymentId(),e.version(),otherConnection,e.intent(),e.create(),e.abort(),e.snapshot()),
            com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(e.paymentId(),e.version(),e.flow(),e.intent(),new NewFlowClosureObservation.RequestBinding(10,"01".repeat(32)),e.abort(),e.snapshot()),
            com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(e.paymentId(),e.version(),e.flow(),e.intent(),e.create(),Optional.of(new NewFlowClosureObservation.RequestBinding(11,"00".repeat(32))),e.snapshot()),
            com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(e.paymentId(),e.version(),e.flow(),new NewFlowClosureObservation.AbortIntent(UUID.randomUUID(),e.intent().requestedAt(),"CUSTOMER","CUSTOMER_REQUEST",3),e.create(),e.abort(),e.snapshot()));
        for(var other:variants)assertThatThrownBy(()->value.requireMatches(other)).isInstanceOf(IllegalStateException.class);
        value.requireMatches(e);
        assertThat(Arrays.stream(value.getClass().getDeclaredMethods()).filter(m->java.lang.reflect.Modifier.isPublic(m.getModifiers()))
            .noneMatch(m->java.lang.reflect.Modifier.isStatic(m.getModifiers())&&m.getReturnType()==value.getClass())).isTrue();
    }
    @Test void expectedContextHasNoPublicConstructorOrFactory(){
        var type=com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected.class;
        assertThat(Arrays.stream(type.getDeclaredConstructors()).noneMatch(c->java.lang.reflect.Modifier.isPublic(c.getModifiers()))).isTrue();
        assertThat(Arrays.stream(type.getDeclaredMethods()).noneMatch(m->java.lang.reflect.Modifier.isPublic(m.getModifiers())
            &&java.lang.reflect.Modifier.isStatic(m.getModifiers())&&m.getReturnType()==type)).isTrue();
    }
    @Test void consumedBoundTokenCannotBeCheckedFreshAgain(){
        response(body());var value=gateway.observeNewFlowClosure(expected());assertThat(value.consumeBound()).isEqualTo(expected());
        assertThatThrownBy(value::requireFresh).isInstanceOf(IllegalStateException.class);
    }
    @Test void wrongActiveConnectionPreventsGet(){
        var e=expected();var other=com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(e.paymentId(),e.version(),
            new NewFlowTerminationEvidence.Expected(1,OP,"pfw_new",910,new CaptureConnectionIdentity("wrong","account","TEST","https://api.pay.jp")),
            e.intent(),e.create(),e.abort(),e.snapshot());
        assertThatThrownBy(()->gateway.observeNewFlowClosure(other)).isInstanceOf(IllegalStateException.class);server.verify();
    }
    @Test void bindingsAndSnapshotCannotBeSubstituted(){
        response(body());var e=expected();var value=gateway.observeNewFlowClosure(e);
        var other=com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(e.paymentId(),e.version()+1,e.flow(),e.intent(),e.create(),e.abort(),e.snapshot());
        assertThatThrownBy(()->value.requireMatches(other)).isInstanceOf(IllegalStateException.class);
        var changed=com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(e.paymentId(),e.version(),e.flow(),e.intent(),e.create(),e.abort(),
            new NewFlowClosureObservation.FinancialSnapshot("changed",e.snapshot().transactions(),"ABSENT"));
        assertThatThrownBy(()->value.consumeFor(changed)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(value::requireFresh).isInstanceOf(IllegalStateException.class);
        assertThat(java.io.Serializable.class.isAssignableFrom(value.getClass())).isFalse();
        assertThat(Arrays.stream(value.getClass().getDeclaredConstructors()).allMatch(c->java.lang.reflect.Modifier.isPrivate(c.getModifiers()))).isTrue();
    }
    @Test void slowGetAndBackwardsCompletionRejected(){
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_new")).andRespond(request->{
            nanos.addAndGet(5_000_000_001L);return withSuccess(body(),MediaType.APPLICATION_JSON).createResponse(request);
        });
        assertThatThrownBy(()->gateway.observeNewFlowClosure(expected())).isInstanceOf(RuntimeException.class);server.verify();
    }
    @Test void backwardsWallClockDuringGetRejected(){
        var wall=new java.util.concurrent.atomic.AtomicReference<>(now);
        clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return wall.get();}};
        setup();
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_new")).andRespond(request->{
            wall.set(now.minusSeconds(1));return withSuccess(body(),MediaType.APPLICATION_JSON).createResponse(request);
        });
        assertThatThrownBy(()->gateway.observeNewFlowClosure(expected())).isInstanceOf(RuntimeException.class);
    }
    @Test void backwardsWallClockAfterObservationRejected(){
        var wall=new java.util.concurrent.atomic.AtomicReference<>(now);
        clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return wall.get();}};
        setup();response(body());var value=gateway.observeNewFlowClosure(expected());wall.set(now.minusSeconds(1));
        assertThatThrownBy(value::requireFresh).isInstanceOf(IllegalStateException.class);
    }
    @Test void concurrentConsumptionHasOnlyOneWinner() throws Exception{
        response(body());var value=gateway.observeNewFlowClosure(expected());var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var gate=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.Callable<Boolean> call=()->{gate.await();try{value.consumeFor(expected());return true;}catch(IllegalStateException rejected){return false;}};
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();assertThat((a.get()?1:0)+(b.get()?1:0)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void failedConsumptionAlsoBurnsObservation(){
        response(body());var value=gateway.observeNewFlowClosure(expected());nanos.addAndGet(5_000_000_001L);
        assertThatThrownBy(()->value.consumeFor(expected())).isInstanceOf(IllegalStateException.class);nanos.set(100);
        assertThatThrownBy(()->value.consumeFor(expected())).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={400,404,409,422,429,500})
    void httpFailureDoesNotRetainBodyOrRetry(int status){
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_new"))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("sensitive-never-retained").contentType(MediaType.APPLICATION_JSON));
        var failure=catchThrowable(()->gateway.observeNewFlowClosure(expected()));assertThat(failure).hasNoCause();
        assertThat(failure.toString()).doesNotContain("sensitive-never-retained");server.verify();
    }
    @Test void futureCompletionRelativeToCurrentClockRejected(){
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return reads.getAndIncrement()==1?now.plusSeconds(1):now;}};
        setup();response(body());assertThatThrownBy(()->gateway.observeNewFlowClosure(expected())).isInstanceOf(RuntimeException.class);
    }
    @Test void wrongContextConsumptionIsIrreversible(){
        response(body());var value=gateway.observeNewFlowClosure(expected());var e=expected();
        var other=com.example.ecsite.service.orderchange.ClosureObservationFixture.expected(99,e.version(),e.flow(),e.intent(),e.create(),e.abort(),e.snapshot());
        assertThatThrownBy(()->value.consumeFor(other)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->value.consumeFor(e)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "{\"order_id\":2,\"order_change_operation_id\":\"12345678-1234-1234-1234-123456789abc\"}",
        "{\"order_id\":\"2\",\"order_change_operation_id\":\"12345678-1234-1234-1234-123456789abc\"}",
        "{\"order_id\":\"1\",\"order_change_operation_id\":\"other\"}"})
    void rejectsIndividualMetadataValueMismatch(String metadata){
        var object=(tools.jackson.databind.node.ObjectNode)JsonMapper.builder().build().readTree(body());
        object.set("metadata",JsonMapper.builder().build().readTree(metadata));response(object.toString());
        assertThatThrownBy(()->gateway.observeNewFlowClosure(expected())).isInstanceOf(RuntimeException.class);
    }

}
