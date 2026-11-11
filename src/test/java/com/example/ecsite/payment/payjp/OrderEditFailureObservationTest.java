package com.example.ecsite.payment.payjp;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import com.example.ecsite.config.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.orderchange.ThreeDsFailureEvidence.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Mock HTTP only. No socket, provider key or financial POST. */
class OrderEditFailureObservationTest {
    final CaptureConnectionIdentity connection=new CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp");
    final UUID operation=UUID.randomUUID();
    final Ownership owner=new Ownership(UUID.randomUUID(),UUID.randomUUID(),operation,1,UUID.randomUUID(),10,11,12,13,"pfw_fixture",2190,0,connection);
    final Context context=new Context(owner,0,2,State.REQUESTED,new byte[32],new byte[32],1,null);
    final LocalFacts local=new LocalFacts(owner,2,"AUTHORIZED","PENDING","PENDING",false,false,false,false,false,false,List.of(),new byte[32],null);
    String body(){return """
        {"id":"pfw_fixture","status":"requires_payment_method","amount":2190,"currency":"jpy","capture_method":"manual","livemode":false,
         "amount_received":null,"amount_capturable":null,"next_action":{},"canceled_at":null,
         "last_payment_error":{"code":"three_d_secure_failed"},
         "metadata":{"order_id":"1","order_change_operation_id":"%s"},"client_secret":"must-not-survive"}
        """.formatted(operation);}
    PayJpPaymentGateway gateway(RestClient client){return new PayJpPaymentGateway(client,new PayJpAuthorizationMapper(),
        new PayJpProperties("pk_test_dummy","sk_test_dummy","https://api.pay.jp","dummy"),
        new CaptureConnectionProperties("test","account","TEST","https://api.pay.jp"),Clock.systemUTC(),System::nanoTime);}
    @Test void onlyGetMintsStrictFailureEvidenceWithoutRetainingSecrets(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=gateway(builder.baseUrl("https://api.pay.jp").build());
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_fixture"))
            .andExpect(method(org.springframework.http.HttpMethod.GET)).andRespond(withSuccess(body(),MediaType.APPLICATION_JSON));
        var observation=gateway.observeOrderEditFailure(context,local);
        assertThat(observation.kind()).isEqualTo(Kind.THREE_DS_FAILURE);
        assertThat(observation.document()).contains("\"amount_received\":null").doesNotContain("client_secret","must-not-survive");
        server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"requires_capture","processing","succeeded","canceled","requires_action"})
    void otherFinancialStatesCannotClose(String state){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=gateway(builder.baseUrl("https://api.pay.jp").build());
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_fixture"))
            .andRespond(withSuccess(body().replace("requires_payment_method",state),MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->gateway.observeOrderEditFailure(context,local)).isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);
        server.verify();
    }
}
