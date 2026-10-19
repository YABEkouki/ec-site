package com.example.ecsite.payment.payjp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import com.example.ecsite.config.PayJpProperties;

class PayJpPaymentGatewayIdentityTest {
    @ParameterizedTest @NullSource @ValueSource(strings={"pf_other","", " ", "\t"})
    void authorizationGetRejectsUnexpectedFlowId(String id) {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        try(var factory=mockStatic(RestClient.class)) {
            factory.when(RestClient::builder).thenReturn(builder);
            var gateway=new PayJpPaymentGateway(builder.baseUrl("http://localhost:1").build(), new PayJpAuthorizationMapper());
            server.expect(requestTo("http://localhost:1/v2/payment_flows/pf_expected"))
                    .andRespond(withSuccess("{\"id\":"+(id==null?"null":"\""+id.replace("\t","\\t")+"\"")+",\"status\":\"requires_capture\"}",MediaType.APPLICATION_JSON));
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<com.example.ecsite.payment.AuthorizationRecovery> save=mock(java.util.function.Consumer.class);
            assertThatThrownBy(()->save.accept(gateway.retrieveAuthorization("pf_expected")))
                    .isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);
            verifyNoInteractions(save);
            server.verify();
        }
    }
    @Test void verifiedAuthorizationFlowIdIsPreservedInResult() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new PayJpPaymentGateway(builder.baseUrl("http://localhost:1").build(),new PayJpAuthorizationMapper());
        server.expect(requestTo("http://localhost:1/v2/payment_flows/pfw_expected"))
                .andRespond(withSuccess("{\"id\":\"pfw_expected\",\"status\":\"requires_capture\"}",MediaType.APPLICATION_JSON));
        var result=gateway.retrieveAuthorization("pfw_expected").result();
        assertThat(result.status()).isEqualTo(com.example.ecsite.payment.AuthorizationResultStatus.AUTHORIZED);
        assertThat(result.providerTransactionId()).isEqualTo("pfw_expected");server.verify();
    }
    @Test void paymentFlowGetRejectsUnexpectedFlowId() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        try(var factory=mockStatic(RestClient.class)) {
            factory.when(RestClient::builder).thenReturn(builder);
            var gateway=new PayJpPaymentGateway(builder.baseUrl("http://localhost:1").build(), new PayJpAuthorizationMapper());
            server.expect(requestTo("http://localhost:1/v2/payment_flows/pf_expected"))
                    .andRespond(withSuccess("{\"id\":\"pf_other\",\"status\":\"succeeded\"}",MediaType.APPLICATION_JSON));
            assertThatThrownBy(()->gateway.retrievePaymentFlow("pf_expected")).isInstanceOf(com.example.ecsite.payment.PaymentGatewayException.class);
            server.verify();
        }
    }
    @Test void httpFailureDoesNotExposeRawSensitiveResponse() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        try (var factory = mockStatic(RestClient.class)) {
            factory.when(RestClient::builder).thenReturn(builder);
            var gateway = new PayJpPaymentGateway(builder.baseUrl("http://localhost:1").build(), new PayJpAuthorizationMapper());
            server.expect(requestTo("http://localhost:1/v2/payment_flows/pf_expected"))
                    .andRespond(withServerError().body("sensitive-client-secret"));
            var failure = catchThrowable(() -> gateway.retrievePaymentFlow("pf_expected"));
            assertThat(failure).hasNoCause();
            assertThat(failure.toString()).doesNotContain("sensitive-client-secret");
            server.verify();
        }
    }
}
