package com.example.ecsite.payment.payjp;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.example.ecsite.config.*;
import com.example.ecsite.payment.*;

/** Exercises the production HTTP client against loopback only. */
class PayJpPaymentGatewaySafetyTest {
    HttpServer server;
    ExecutorService executor;
    PayJpPaymentGateway gateway;
    AtomicInteger calls = new AtomicInteger();
    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        gateway = new PayJpPaymentGateway(new PayJpProperties("dummy", "dummy", base(), "dummy"),
                new PayJpAuthorizationMapper(), new PayJpSafetyProperties(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofHours(23)));
    }
    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    @AfterEach void close() throws Exception {
        gateway.close(); server.stop(0); executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    void respond(int status, String body) {
        server.createContext("/v2/payment_flows", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
    }
    @ParameterizedTest @CsvSource({
        "409,idempotency_conflict,IDEMPOTENCY_PARAMETER_CONFLICT",
        "409,idempotency_timeout,IDEMPOTENCY_WAIT_TIMEOUT",
        "409,unknown_code,OTHER_HTTP_ERROR",
        "400,idempotency_timeout,OTHER_HTTP_ERROR",
        "500,internal_server_error,SERVER_RESULT_UNKNOWN",
        "502,unknown_code,SERVER_RESULT_UNKNOWN",
        "503,idempotency_infrastructure_error,SERVER_RESULT_UNKNOWN",
        "504,timeout,SERVER_RESULT_UNKNOWN"})
    void classifiesOfficialRootCodesWithoutRetryOrSensitiveCause(int status, String code, PaymentGatewayErrorCategory category) {
        respond(status, "{\"code\":\"" + code + "\",\"detail\":\"private-card-client-secret\"}");
        var failure = catchThrowableOfType(() -> gateway.prepareAuthorization(new AuthorizationRequest(3000, "same-key")), PaymentGatewayException.class);
        assertThat(failure.category()).isEqualTo(category);
        assertThat(failure.httpStatus()).isEqualTo(status);
        assertThat(failure.providerErrorCode()).isEqualTo(code);
        assertThat(failure.resultUnknown()).isTrue();
        assertThat(failure).hasNoCause();
        assertThat(failure.toString()).doesNotContain("private-card-client-secret", "same-key");
        assertThat(calls).hasValue(1);
    }
    @Test void unknown409NeverGuessesFromNestedOrHumanMessage() {
        respond(409, "{\"error\":{\"code\":\"idempotency_conflict\"},\"detail\":\"idempotency_timeout\"}");
        var failure = catchThrowableOfType(() -> gateway.prepareAuthorization(new AuthorizationRequest(3000, "key")), PaymentGatewayException.class);
        assertThat(failure.category()).isEqualTo(PaymentGatewayErrorCategory.OTHER_HTTP_ERROR);
        assertThat(failure.providerErrorCode()).isNull();
    }
    @Test void redirectsDoNotReplayPost() {
        server.createContext("/v2/payment_flows", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", base() + "/redirected");
            exchange.sendResponseHeaders(307, -1); exchange.close();
        });
        server.createContext("/redirected", exchange -> { calls.incrementAndGet(); exchange.close(); });
        assertThatThrownBy(() -> gateway.prepareAuthorization(new AuthorizationRequest(3000, "key")))
                .isInstanceOf(PaymentGatewayException.class);
        assertThat(calls).hasValue(1);
    }
    @Test void disconnectDoesNotReplayPost() {
        server.createContext("/v2/payment_flows", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.close();
        });
        var failure = catchThrowableOfType(() -> gateway.prepareAuthorization(new AuthorizationRequest(3000, "key")), PaymentGatewayException.class);
        assertThat(failure.category()).isEqualTo(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN);
        assertThat(calls).hasValue(1);
    }
    @Test void readTimeoutIsAppliedAndDoesNotRetry() {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/v2/payment_flows", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                var failure = catchThrowableOfType(() -> gateway.prepareAuthorization(new AuthorizationRequest(3000, "key")), PaymentGatewayException.class);
                assertThat(failure.category()).isEqualTo(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN);
            });
            assertThat(calls).hasValue(1);
        } finally { release.countDown(); }
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"null", "{}", "{\"id\":\"pf\"}", "invalid-json"})
    void invalidResponseRemainsUnknown(String body) {
        respond(200, body);
        var failure = catchThrowableOfType(() -> gateway.prepareAuthorization(new AuthorizationRequest(3000, "key")), PaymentGatewayException.class);
        assertThat(failure.category()).isEqualTo(PaymentGatewayErrorCategory.INVALID_RESPONSE);
        assertThat(failure.resultUnknown()).isTrue();
    }
    @Test void productionClientReceivesBothTimeoutConfigurations() throws Exception {
        gateway.close();
        gateway = new PayJpPaymentGateway(new PayJpProperties("dummy", "dummy", base(), "dummy"),
                new PayJpAuthorizationMapper(), new PayJpSafetyProperties(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofHours(23)));
        Object client = org.springframework.test.util.ReflectionTestUtils.getField(gateway, "httpClient");
        Object manager = org.springframework.test.util.ReflectionTestUtils.getField(client, "connManager");
        org.apache.hc.client5.http.config.ConnectionConfig config = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                manager, "resolveConnectionConfig", new org.apache.hc.client5.http.HttpRoute(org.apache.hc.core5.http.HttpHost.create(base())));
        assertThat(config.getConnectTimeout().toMilliseconds()).isEqualTo(5000);
        assertThat(config.getSocketTimeout().toMilliseconds()).isEqualTo(15000);
        org.apache.hc.client5.http.config.RequestConfig requestConfig = (org.apache.hc.client5.http.config.RequestConfig)
                org.springframework.test.util.ReflectionTestUtils.getField(client, "defaultConfig");
        assertThat(requestConfig.getResponseTimeout().toMilliseconds()).isEqualTo(15000);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"redirect","disconnect","timeout","server-error"})
    void captureByteTransportStillNeverRetriesOrFollowsRedirects(String failure) {
        // Keep the production Apache transport on loopback while supplying its validated logical identity.
        var identity=new com.example.ecsite.payment.capture.CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp");
        org.springframework.test.util.ReflectionTestUtils.setField(gateway,"activeConnection",new PayJpProperties("pk_test_dummy","sk_test_dummy","https://api.pay.jp","test"));
        org.springframework.test.util.ReflectionTestUtils.setField(gateway,"captureConnection",new CaptureConnectionProperties("test","account","TEST","https://api.pay.jp"));
        var release=new CountDownLatch(1);
        server.createContext("/v2/payment_flows",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            try{
                if(failure.equals("redirect")){exchange.getResponseHeaders().set("Location",base()+"/redirected");exchange.sendResponseHeaders(307,-1);}
                else if(failure.equals("timeout"))release.await(5,TimeUnit.SECONDS);
                else if(failure.equals("server-error"))exchange.sendResponseHeaders(500,-1);
            }catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}
        });
        server.createContext("/redirected",exchange->{calls.incrementAndGet();exchange.close();});
        try{
            assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertThatThrownBy(()->gateway.capture(com.example.ecsite.payment.capture.CaptureHttpRequest.create(identity,"pfw_capture",3000,"same-key"))).isInstanceOf(PaymentGatewayException.class));
            assertThat(calls).hasValue(1);
        }finally{release.countDown();}
    }
    private void assertTimeoutPreemptively(Duration limit, org.junit.jupiter.api.function.Executable task) {
        Assertions.assertTimeoutPreemptively(limit, task);
    }
}
