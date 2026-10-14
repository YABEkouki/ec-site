package com.example.ecsite.payment.payjp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import com.example.ecsite.config.PayJpProperties;

class PayJpPaymentGatewayIdentityTest {
    @ParameterizedTest @ValueSource(strings={"pf_other",""})
    void authorizationGetRejectsUnexpectedFlowId(String id) {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        try(var factory=mockStatic(RestClient.class)) {
            factory.when(RestClient::builder).thenReturn(builder);
            var gateway=new PayJpPaymentGateway(new PayJpProperties("dummy","dummy","http://localhost:1","dummy"),new PayJpAuthorizationMapper());
            server.expect(requestTo("http://localhost:1/v2/payment_flows/pf_expected"))
                    .andRespond(withSuccess("{\"id\":\""+id+"\",\"status\":\"requires_capture\"}",MediaType.APPLICATION_JSON));
            assertThatThrownBy(()->gateway.retrieveAuthorization("pf_expected")).isInstanceOf(IllegalStateException.class);
            server.verify();
        }
    }
    @Test void paymentFlowGetRejectsUnexpectedFlowId() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        try(var factory=mockStatic(RestClient.class)) {
            factory.when(RestClient::builder).thenReturn(builder);
            var gateway=new PayJpPaymentGateway(new PayJpProperties("dummy","dummy","http://localhost:1","dummy"),new PayJpAuthorizationMapper());
            server.expect(requestTo("http://localhost:1/v2/payment_flows/pf_expected"))
                    .andRespond(withSuccess("{\"id\":\"pf_other\",\"status\":\"succeeded\"}",MediaType.APPLICATION_JSON));
            assertThatThrownBy(()->gateway.retrievePaymentFlow("pf_expected")).isInstanceOf(IllegalStateException.class);
            server.verify();
        }
    }
}
