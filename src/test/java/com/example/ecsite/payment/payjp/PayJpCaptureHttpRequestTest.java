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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/** Logical production origin stays validated; only the test request factory routes transport to loopback. */
class PayJpCaptureHttpRequestTest {
    HttpServer server; PayJpPaymentGateway gateway;
    CaptureConnectionIdentity identity=new CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp");
    AtomicReference<byte[]> body=new AtomicReference<>();
    AtomicReference<String> contentType=new AtomicReference<>(),key=new AtomicReference<>(),path=new AtomicReference<>();
    CaptureHttpRequest request() {return CaptureHttpRequest.create(identity,"pfw_capture",5500,"saved-key");}
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();
        var properties=new PayJpProperties("pk_test_dummy","sk_test_dummy","https://api.pay.jp","test");
        gateway=new PayJpPaymentGateway(properties,new PayJpAuthorizationMapper(),new PayJpSafetyProperties(Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofHours(23)),new CaptureConnectionProperties("test","account","TEST","https://api.pay.jp"));
        var delegate=new SimpleClientHttpRequestFactory();
        var client=RestClient.builder().baseUrl(properties.apiBaseUrl())
            .requestFactory((uri,method)->delegate.createRequest(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+uri.getRawPath()),method)).build();
        ReflectionTestUtils.setField(gateway,"restClient",client);
    }
    @AfterEach void cleanup() throws Exception {TransactionSynchronizationManager.setActualTransactionActive(false);gateway.close();server.stop(0);}
    void respond(String flow) {
        server.createContext("/",e->{path.set(e.getRequestURI().getPath());body.set(e.getRequestBody().readAllBytes());contentType.set(e.getRequestHeaders().getFirst("Content-Type"));key.set(e.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] response=("{\"id\":\""+flow+"\",\"status\":\"succeeded\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);e.close();});
    }
    @Test void savedByteArrayIsTheExactReceivedBodyWithSavedPathKeyAndExplicitJson() {
        respond("pfw_capture");var original=request();
        var saved=CaptureHttpRequest.restore(identity,original.flowId(),5500,"saved-key",original.method(),original.path(),original.contentType(),1,original.body(),1,original.fingerprint());
        assertThat(gateway.capture(saved).status()).isEqualTo(CaptureResultStatus.CAPTURED);
        assertThat(body.get()).isEqualTo(saved.body());assertThat(contentType.get()).isEqualTo("application/json");
        assertThat(key.get()).isEqualTo("saved-key");assertThat(path.get()).isEqualTo(saved.path());
    }
    @Test void mismatchedResponseFlowRemainsInvalidResponse() {respond("pfw_other");assertThatThrownBy(()->gateway.capture(request())).isInstanceOfSatisfying(PaymentGatewayException.class,e->assertThat(e.category()).isEqualTo(PaymentGatewayErrorCategory.INVALID_RESPONSE));}
    @Test void activeTransactionIsRejectedBeforeHttp() {respond("pfw_capture");TransactionSynchronizationManager.setActualTransactionActive(true);assertThatThrownBy(()->gateway.capture(request())).isInstanceOf(IllegalStateException.class);assertThat(body.get()).isNull();}
    @ParameterizedTest @ValueSource(strings={"connection","account","mode"}) void otherConnectionIdentityIsRejectedBeforeHttp(String field) {
        respond("pfw_capture");var other=new CaptureConnectionIdentity(field.equals("connection")?"other":"test",field.equals("account")?"other":"account",field.equals("mode")?"LIVE":"TEST","https://api.pay.jp");
        assertThatThrownBy(()->gateway.capture(CaptureHttpRequest.create(other,"pfw_capture",5500,"saved-key"))).isInstanceOf(IllegalStateException.class);assertThat(body.get()).isNull();
    }
}
