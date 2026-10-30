package com.example.ecsite.payment.payjp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import static org.assertj.core.api.Assertions.*;
class OrderAuthorizationObservationTest {
    static final CaptureConnectionIdentity C=new CaptureConnectionIdentity("test","merchant","TEST","https://api.pay.jp");
    static final OrderAuthorizationObservation.Expected E=new OrderAuthorizationObservation.Expected(1,2,3,"pfw_test",6000,0,"ab".repeat(32),C,null);
    @Test void opaqueReceiptIsFreshBoundAndOneUse() {
        var nanos=new AtomicLong();var obs=MockOrderAuthorizationTransport.observe(E,Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC),nanos::get);
        obs.consume(E);assertThat(obs.payload()).doesNotContain("client_secret");
        assertThatThrownBy(()->obs.consume(E)).isInstanceOf(IllegalStateException.class);
        nanos.set(5_000_000_001L);assertThatThrownBy(()->obs.requireConsumed(E)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"captured","canceled","requires_action","unknown"})
    void nonAuthorizationStatesAreRejected(String status) {
        String body="{\"id\":\"pfw_test\",\"status\":\""+status+"\",\"currency\":\"jpy\",\"capture_method\":\"manual\",\"amount\":6000,\"amount_capturable\":6000,\"amount_received\":0,\"livemode\":false}";
        assertThatThrownBy(()->MockOrderAuthorizationTransport.observeRaw(E,body,Clock.systemUTC(),System::nanoTime)).isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);
    }
    @ParameterizedTest @ValueSource(strings={"amount","amount_capturable","id","currency","capture_method","livemode","amount_received"})
    void providerIdentityAndMoneyAreStrict(String field) {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var body=(tools.jackson.databind.node.ObjectNode)mapper.readTree("{\"id\":\"pfw_test\",\"status\":\"requires_capture\",\"currency\":\"jpy\",\"capture_method\":\"manual\",\"amount\":6000,\"amount_capturable\":6000,\"amount_received\":0,\"livemode\":false}");
        body.put(field,"invalid");
        assertThatThrownBy(()->MockOrderAuthorizationTransport.observeRaw(E,body.toString(),Clock.systemUTC(),System::nanoTime)).isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);
    }
    @Test void changedTargetBurnsObservation() {
        var obs=MockOrderAuthorizationTransport.observe(E);
        var other=new OrderAuthorizationObservation.Expected(1,2,3,"pfw_test",6000,0,"cd".repeat(32),C,null);
        assertThatThrownBy(()->obs.consume(other)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->obs.consume(E)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"expired","invalidExpiry","canceled","captured","getTooSlow"})
    void contradictoryTimestampsAndSlowGetAreRejected(String kind) {
        var m=tools.jackson.databind.json.JsonMapper.builder().build();
        var body=(tools.jackson.databind.node.ObjectNode)m.readTree("{\"id\":\"pfw_test\",\"status\":\"requires_capture\",\"currency\":\"jpy\",\"capture_method\":\"manual\",\"amount\":6000,\"amount_capturable\":6000,\"amount_received\":0,\"livemode\":false}");
        switch(kind) {
            case "expired" -> body.put("expired_at","2020-01-01T00:00:00Z");
            case "invalidExpiry" -> body.put("expired_at","unknown");
            case "canceled" -> body.put("canceled_at","2020-01-01T00:00:00Z");
            case "captured" -> body.put("captured_at","2020-01-01T00:00:00Z");
        }
        var ticks=new AtomicLong();java.util.function.LongSupplier ticker=kind.equals("getTooSlow")?()->ticks.getAndAdd(6_000_000_000L):System::nanoTime;
        assertThatThrownBy(()->MockOrderAuthorizationTransport.observeRaw(E,body.toString(),Clock.systemUTC(),ticker)).isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);
    }
    @Test void reauthorizationObservationRequiresItsActualMetadata() {
        var op=java.util.UUID.randomUUID();var target=new OrderAuthorizationObservation.Expected(1,2,3,"pfw_test",6000,0,"ab".repeat(32),C,op);
        assertThat(MockOrderAuthorizationTransport.observe(target)).isNotNull();
        String body="{\"id\":\"pfw_test\",\"status\":\"requires_capture\",\"currency\":\"jpy\",\"capture_method\":\"manual\",\"amount\":6000,\"amount_capturable\":6000,\"amount_received\":0,\"livemode\":false}";
        assertThatThrownBy(()->MockOrderAuthorizationTransport.observeRaw(target,body,Clock.systemUTC(),System::nanoTime)).isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);
    }
    @ParameterizedTest @ValueSource(ints={500,503,429,401,-1})
    void httpAndTransportFailuresDoNotMintObservation(int status) {
        assertThatThrownBy(()->MockOrderAuthorizationTransport.observeRaw(E,"{}",Clock.systemUTC(),System::nanoTime,status)).isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);
    }
}
