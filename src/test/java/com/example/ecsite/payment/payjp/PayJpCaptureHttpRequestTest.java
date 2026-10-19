package com.example.ecsite.payment.payjp;

import static org.assertj.core.api.Assertions.*;
import com.example.ecsite.config.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.capture.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/** Logical production origin stays validated; only the test request factory routes transport to loopback. */
class PayJpCaptureHttpRequestTest {
    HttpServer server; PayJpPaymentGateway gateway;
    CaptureConnectionIdentity identity=new CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp");
    java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
    AtomicReference<byte[]> body=new AtomicReference<>();
    AtomicReference<String> contentType=new AtomicReference<>(),key=new AtomicReference<>(),path=new AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean authorizationMatches=new java.util.concurrent.atomic.AtomicBoolean();
    AtomicReference<String> method=new AtomicReference<>();
    AtomicReference<URI> logicalDestination=new AtomicReference<>();
    CaptureHttpRequest request() {return CaptureHttpRequest.create(identity,"pfw_capture",5500,"saved-key");}
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();
        var properties=new PayJpProperties("pk_test_dummy","sk_test_dummy","https://api.pay.jp","test");
        gateway=new PayJpPaymentGateway(properties,new PayJpAuthorizationMapper(),new PayJpSafetyProperties(Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofHours(23)),new CaptureConnectionProperties("test","account","TEST","https://api.pay.jp"));
        var original=(RestClient)ReflectionTestUtils.getField(gateway,"restClient");
        var delegate=(ClientHttpRequestFactory)ReflectionTestUtils.getField(original,"clientRequestFactory");
        // Preserve production converters, Authorization and Apache transport safeguards.
        // Rewrite only the physical destination at the final test transport boundary.
        var client=original.mutate().requestFactory((uri,httpMethod)->{
            logicalDestination.set(uri);
            return delegate.createRequest(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+uri.getRawPath()),httpMethod);
        }).build();
        ReflectionTestUtils.setField(gateway,"restClient",client);
    }
    @AfterEach void cleanup() throws Exception {TransactionSynchronizationManager.setActualTransactionActive(false);gateway.close();server.stop(0);}
    void respond(String flow) {
        server.createContext("/",e->{
            calls.incrementAndGet();
            // Keep only the comparison outcome; never retain/log the received credential.
            authorizationMatches.set(("Bearer "+"sk_test_dummy").equals(e.getRequestHeaders().getFirst("Authorization")));
            method.set(e.getRequestMethod());path.set(e.getRequestURI().getPath());body.set(e.getRequestBody().readAllBytes());contentType.set(e.getRequestHeaders().getFirst("Content-Type"));key.set(e.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] response=("{\"id\":\""+flow+"\",\"status\":\"succeeded\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);e.close();});
    }
    @Test void savedByteArrayIsTheExactReceivedBodyWithSavedPathKeyAndExplicitJson() {
        respond("pfw_capture");var original=request();
        var saved=CaptureHttpRequest.restore(identity,original.flowId(),5500,"saved-key",original.method(),original.path(),original.contentType(),1,original.body(),1,original.fingerprint());
        assertThat(gateway.capture(saved).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        assertThat(body.get()).isEqualTo(saved.body());assertThat(contentType.get()).isEqualTo("application/json");
        assertThat(key.get()).isEqualTo("saved-key");assertThat(path.get()).isEqualTo(saved.path());
        assertThat(method.get()).isEqualTo("POST");assertThat(authorizationMatches.get()).as("Authorization matches configured test credential").isTrue();
        assertThat(logicalDestination.get()).isEqualTo(URI.create(saved.connection().apiOrigin()+saved.path()));
    }
    @Test void mismatchedResponseFlowRemainsInvalidResponse() {respond("pfw_other");assertThatThrownBy(()->gateway.capture(request())).isInstanceOfSatisfying(PaymentGatewayException.class,e->assertThat(e.category()).isEqualTo(PaymentGatewayErrorCategory.INVALID_RESPONSE));}
    @Test void activeTransactionIsRejectedBeforeHttp() {respond("pfw_capture");TransactionSynchronizationManager.setActualTransactionActive(true);assertThatThrownBy(()->gateway.capture(request())).isInstanceOf(IllegalStateException.class);assertThat(body.get()).isNull();}
    @ParameterizedTest @ValueSource(strings={"connection","account","mode"}) void otherConnectionIdentityIsRejectedBeforeHttp(String field) {
        respond("pfw_capture");var other=new CaptureConnectionIdentity(field.equals("connection")?"other":"test",field.equals("account")?"other":"account",field.equals("mode")?"LIVE":"TEST","https://api.pay.jp");
        assertThatThrownBy(()->gateway.capture(CaptureHttpRequest.create(other,"pfw_capture",5500,"saved-key"))).isInstanceOf(IllegalStateException.class);assertThat(body.get()).isNull();
    }    @ParameterizedTest @ValueSource(strings={"connection","account","mode","origin","key-mode"})
    void configurationChangesRejectSavedRequestBeforeOpeningHttp(String field) {
        respond("pfw_capture");
        var changed=new CaptureConnectionProperties(field.equals("connection")?"other":"test",field.equals("account")?"other":"account",field.equals("mode")?"LIVE":"TEST",field.equals("origin")?"https://other.invalid":"https://api.pay.jp");
        ReflectionTestUtils.setField(gateway,"captureConnection",changed);
        if(field.equals("key-mode"))ReflectionTestUtils.setField(gateway,"activeConnection",new PayJpProperties("pk_test_dummy","sk_live_dummy","https://api.pay.jp","test"));
        assertThatThrownBy(()->gateway.capture(request())).isInstanceOf(IllegalStateException.class);
        assertThat(body.get()).isNull();assertThat(logicalDestination.get()).isNull();
    }
    @Test void httpDataAloneIsReusableAndIsNotAnExecutionPermit() {
        respond("pfw_capture");var http=request();
        // Deliberately call the internal Gateway directly, outside the business facade.
        // InitialPost's once-only guarantee does not belong to this value object/transport API.
        gateway.capture(http);gateway.capture(http);assertThat(calls.get()).isEqualTo(2);
        assertThat(method.get()).isEqualTo("POST");
        assertThat(authorizationMatches.get()).isTrue();
    }

}
