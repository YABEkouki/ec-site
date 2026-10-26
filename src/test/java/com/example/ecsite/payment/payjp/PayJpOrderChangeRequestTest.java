package com.example.ecsite.payment.payjp;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.ecsite.config.*;
import com.example.ecsite.payment.*;
import com.example.ecsite.payment.capture.*;
import com.example.ecsite.payment.orderchange.*;

class PayJpOrderChangeRequestTest {
    MockRestServiceServer server;
    PayJpPaymentGateway gateway;
    final CaptureConnectionIdentity identity=new CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp");
    @BeforeEach void setup(){
        var builder=RestClient.builder().baseUrl("https://api.pay.jp");server=MockRestServiceServer.bindTo(builder).build();
        gateway=new PayJpPaymentGateway(builder.build(),new PayJpAuthorizationMapper());
        ReflectionTestUtils.setField(gateway,"activeConnection",new PayJpProperties("pk_test_dummy","sk_test_dummy","https://api.pay.jp","unused"));
        ReflectionTestUtils.setField(gateway,"captureConnection",new CaptureConnectionProperties("primary","merchant","TEST","https://api.pay.jp"));
    }
    @AfterEach void verify(){TransactionSynchronizationManager.setActualTransactionActive(false);server.verify();}
    OrderChangeHttpRequest create(){return OrderChangeHttpRequest.create(identity,UUID.randomUUID(),10,5500,"fixed-key");}
    StoredOrderChangeRequest saved(OrderChangeHttpRequest request){var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);return new StoredOrderChangeRequest(1,2,3,request,now.minusSeconds(1),now.plusSeconds(100));}
    PayJpPaymentFlowObservation send(StoredOrderChangeRequest saved){
        var store=org.mockito.Mockito.mock(OrderChangeFinancialRequestStore.class);
        org.mockito.Mockito.when(store.findForDispatch(saved.id())).thenReturn(saved);
        var manager=org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(manager.getTransaction(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return new OrderChangeRequestSender(store,gateway,manager).send(saved.id());
    }
    void post(OrderChangeHttpRequest request,int status,String body){
        server.expect(requestTo("https://api.pay.jp"+request.path())).andExpect(method(HttpMethod.POST))
            .andExpect(header("Idempotency-Key",request.idempotencyKey())).andExpect(content().bytes(request.body()))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
    }
    @Test void reusesExactBytesAndKeyWithoutSecretInObservation(){
        var request=create();var saved=saved(request);
        post(request,200,"{\"id\":\"pfw_new\",\"status\":\"requires_payment_method\",\"client_secret\":\"private\"}");
        post(request,200,"{\"id\":\"pfw_new\",\"status\":\"requires_payment_method\"}");
        assertThat(send(saved).id()).isEqualTo("pfw_new");
        assertThat(send(saved).toString()).doesNotContain("private");
    }
    @ParameterizedTest @CsvSource({"409,idempotency_timeout,IDEMPOTENCY_WAIT_TIMEOUT","500,internal_error,SERVER_RESULT_UNKNOWN"})
    void errorsStayUnknownAndDoNotAutomaticallyRetry(int status,String code,PaymentGatewayErrorCategory category){
        var request=create();post(request,status,"{\"code\":\""+code+"\"}");
        assertThatThrownBy(()->send(saved(request))).isInstanceOfSatisfying(PaymentGatewayException.class,
            error->{assertThat(error.resultUnknown()).isTrue();assertThat(error.category()).isEqualTo(category);});
    }
    @Test void expiredRequestNeverPosts(){
        var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var expired=new StoredOrderChangeRequest(1,2,3,create(),now.minusSeconds(100),now.minusSeconds(1));
        assertThatThrownBy(()->send(expired)).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingObservationValuesRemainNullAndUnknownStatusIsRetained(){
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_new")).andRespond(withSuccess("{\"id\":\"pfw_new\",\"status\":\"unknown_future\"}",MediaType.APPLICATION_JSON));
        var result=gateway.observeOrderChangeFlow("pfw_new");
        assertThat(result.amount()).isNull();assertThat(result.amountReceived()).isNull();assertThat(result.amountCapturable()).isNull();
        assertThat(result.livemode()).isNull();assertThat(result.metadata()).isNull();assertThat(result.status()).isEqualTo("unknown_future");
    }
    @Test void cancelRequiresKnownAuthorizedObservationAndUsesSavedKey(){
        var request=OrderChangeHttpRequest.cancel(identity,UUID.randomUUID(),10,5500,"fixed-cancel","pfw_old",false);
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(withSuccess(
            "{\"id\":\"pfw_old\",\"status\":\"requires_capture\",\"amount\":5500,\"currency\":\"jpy\",\"amount_received\":0,\"amount_capturable\":5500,\"capture_method\":\"manual\",\"livemode\":false}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.pay.jp"+request.path())).andExpect(header("Idempotency-Key",request.idempotencyKey()))
            .andExpect(content().bytes(request.body())).andRespond(withException(new java.net.SocketTimeoutException("test timeout")));
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(withSuccess(
            "{\"id\":\"pfw_old\",\"status\":\"requires_capture\",\"amount\":5500,\"currency\":\"jpy\",\"amount_received\":0,\"amount_capturable\":5500,\"capture_method\":\"manual\",\"livemode\":false}",MediaType.APPLICATION_JSON));
        post(request,200,"{\"id\":\"pfw_old\",\"status\":\"canceled\"}");
        var saved=saved(request);
        assertThatThrownBy(()->send(saved)).isInstanceOfSatisfying(PaymentGatewayException.class,error->assertThat(error.resultUnknown()).isTrue());
        assertThat(send(saved).status()).isEqualTo("canceled");
    }
    @Test void unknownCancelStateNeverPosts(){
        var request=OrderChangeHttpRequest.cancel(identity,UUID.randomUUID(),10,5500,"fixed-cancel","pfw_new",true);
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_new")).andRespond(withSuccess("{\"id\":\"pfw_new\",\"status\":\"requires_action\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->send(saved(request))).isInstanceOf(IllegalStateException.class);
    }
    @Test void transactionRejectsBeforeNetwork(){
        var request=saved(create());TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(()->send(request)).isInstanceOf(IllegalStateException.class);
    }
    @Test void transportTimeoutRetainsSameKeyForExplicitRetry(){
        var request=create();var saved=saved(request);
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows")).andExpect(header("Idempotency-Key","fixed-key"))
            .andExpect(content().bytes(request.body())).andRespond(withException(new java.net.SocketTimeoutException("test timeout")));
        post(request,200,"{\"id\":\"pfw_new\",\"status\":\"requires_payment_method\"}");
        assertThatThrownBy(()->send(saved)).isInstanceOfSatisfying(PaymentGatewayException.class,
            error->assertThat(error.category()).isEqualTo(PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN));
        assertThat(send(saved).id()).isEqualTo("pfw_new");
    }
    @Test void completeGetPreservesFinancialEvidence(){
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_new")).andRespond(withSuccess("""
            {"id":"pfw_new","status":"requires_capture","amount":5500,"currency":"jpy","amount_received":0,
             "amount_capturable":5500,"capture_method":"manual","livemode":false,"expired_at":"2026-10-28T00:00:00Z",
             "metadata":{"order_id":"10","order_change_operation_id":"operation"}}
            """,MediaType.APPLICATION_JSON));
        var result=gateway.observeOrderChangeFlow("pfw_new");
        assertThat(result.amount()).isEqualTo(5500L);assertThat(result.amountCapturable()).isEqualTo(5500L);
        assertThat(result.expiredAt()).isEqualTo(OffsetDateTime.parse("2026-10-28T00:00:00Z"));
        assertThat(result.metadata()).containsEntry("order_id","10");
    }
    @Test void unconfirmedGetKeepsNullAmountsAndMetadataTypes(){
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_new")).andRespond(withSuccess("""
            {"id":"pfw_new","status":"requires_payment_method","amount":910,"currency":"jpy",
             "amount_received":null,"amount_capturable":null,"capture_method":"manual","livemode":false,
             "metadata":{"order_id":"1039","order_change_operation_id":"operation"}}
            """,MediaType.APPLICATION_JSON));
        var result=gateway.observeOrderChangeFlow("pfw_new");
        assertThat(result.amountReceived()).isNull();assertThat(result.amountCapturable()).isNull();
        assertThat(result.metadata().get("order_id")).isInstanceOf(String.class).isEqualTo("1039");
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MISSING","0"})
    void missingAndZeroReceivedKeepTheirNullableMapping(String received){
        String field=received.equals("MISSING")?"":",\"amount_received\":0";
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_new")).andRespond(withSuccess(
            "{\"id\":\"pfw_new\",\"status\":\"requires_payment_method\""+field+"}",MediaType.APPLICATION_JSON));
        var result=gateway.observeOrderChangeFlow("pfw_new");
        if(received.equals("MISSING"))assertThat(result.amountReceived()).isNull();
        else assertThat(result.amountReceived()).isEqualTo(0L);
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"\"0\"","0.5","false","{}","[]","9223372036854775808"})
    void receivedAmountRejectsNonIntegerJsonTypes(String received){
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_new")).andRespond(withSuccess(
            "{\"id\":\"pfw_new\",\"status\":\"requires_payment_method\",\"amount_received\":"+received+"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->gateway.observeOrderChangeFlow("pfw_new")).isInstanceOf(PaymentGatewayException.class);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"null","0"})
    void uncapturedAuthorizationPermitsCancelWithFullCapturableAmount(String received){
        var request=OrderChangeHttpRequest.cancel(identity,UUID.randomUUID(),10,5500,"fixed-cancel","pfw_old",false);
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(withSuccess(
            cancelEvidence(received,"5500"),MediaType.APPLICATION_JSON));
        post(request,200,"{\"id\":\"pfw_old\",\"status\":\"canceled\"}");
        assertThat(send(saved(request)).status()).isEqualTo("canceled");
    }
    String cancelEvidence(String received,String capturable){
        return "{\"id\":\"pfw_old\",\"status\":\"requires_capture\",\"amount\":5500,\"currency\":\"jpy\","
            +(received.equals("MISSING")?"":"\"amount_received\":"+received+",")
            +(capturable.equals("MISSING")?"":"\"amount_capturable\":"+capturable+",")
            +"\"capture_method\":\"manual\",\"livemode\":false}";
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"MISSING","null","5499","5501","0","-1"})
    void cancelRequiresExactFullCapturableAmountAndNeverPostsOtherwise(String capturable){
        var request=OrderChangeHttpRequest.cancel(identity,UUID.randomUUID(),10,5500,"fixed-cancel","pfw_old",false);
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(withSuccess(
            cancelEvidence("0",capturable),MediaType.APPLICATION_JSON));
        // No POST expectation: even one unexpected HTTP request fails this test.
        assertThatThrownBy(()->send(saved(request))).isInstanceOf(IllegalStateException.class);
    }
    @Test void explicitReceivedNullAndMissingFieldAreDifferentObservations(){
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(withSuccess(cancelEvidence("null","5500"),MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(withSuccess(cancelEvidence("MISSING","5500"),MediaType.APPLICATION_JSON));
        var explicit=gateway.observeOrderChangeFlow("pfw_old");var absent=gateway.observeOrderChangeFlow("pfw_old");
        assertThat(explicit.amountReceived()).isNull();assertThat(absent.amountReceived()).isNull();
        assertThat(explicit).isNotEqualTo(absent);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"\"5500\"","5500.0","false","[]","{}","9223372036854775808"})
    void capturableRejectsNonIntegerJsonTypes(String value){
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(withSuccess(cancelEvidence("0",value),MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->gateway.observeOrderChangeFlow("pfw_old")).isInstanceOf(PaymentGatewayException.class);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"MISSING","1","-1"})
    void cancelRejectsMissingOrNonzeroReceivedWithoutPosting(String received){
        var request=OrderChangeHttpRequest.cancel(identity,UUID.randomUUID(),10,5500,"fixed-cancel","pfw_old",false);
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(withSuccess(cancelEvidence(received,"5500"),MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->send(saved(request))).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"flow","amount","currency","mode","capture","succeeded","processing"})
    void cancelRejectsIdentityMismatchAndCapturedOrPendingFlowsWithoutPosting(String invalid){
        var request=OrderChangeHttpRequest.cancel(identity,UUID.randomUUID(),10,5500,"fixed-cancel","pfw_old",false);
        String body=cancelEvidence("0","5500");
        body=switch(invalid){
            case "flow"->body.replace("pfw_old","pfw_new");
            case "amount"->body.replace("\"amount\":5500","\"amount\":5501");
            case "currency"->body.replace("jpy","JPY");
            case "mode"->body.replace("false","true");
            case "capture"->body.replace("manual","automatic");
            default->body.replace("requires_capture",invalid);
        };
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_old")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->send(saved(request))).isInstanceOf(invalid.equals("flow")?PaymentGatewayException.class:IllegalStateException.class);
    }

}
