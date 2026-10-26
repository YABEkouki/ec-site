package com.example.ecsite.payment.payjp;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.example.ecsite.config.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Runs the real raw-JSON observer against Mock HTTP only, for database isolation tests. */
public final class MockOldAuthorizationTransport {
    private MockOldAuthorizationTransport(){}
    public static OldAuthorizationObservation observe(OldAuthorizationObservation.Expected expected){
        return observe(expected,java.time.Clock.systemUTC(),System::nanoTime);
    }
    public static OldAuthorizationObservation observe(OldAuthorizationObservation.Expected expected,java.time.Clock clock,java.util.function.LongSupplier ticker){
        var body="""
            {"id":"%s","status":"requires_capture","amount":%d,"currency":"jpy","capture_method":"manual",
             "livemode":%s,"amount_received":null,"amount_capturable":%d}
            """.formatted(expected.oldFlowId(),expected.amount(),expected.connection().mode().equals("LIVE"),expected.amount());
        return observeRaw(expected,body,clock,ticker);
    }
    public static OldAuthorizationObservation observeRaw(OldAuthorizationObservation.Expected expected,String body,
            java.time.Clock clock,java.util.function.LongSupplier ticker){
        var c=expected.connection();var builder=RestClient.builder().baseUrl(c.apiOrigin());
        var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new PayJpPaymentGateway(builder.build(),new PayJpAuthorizationMapper(),
            new PayJpProperties("pk_test_dummy","sk_test_dummy",c.apiOrigin(),"dummy"),
            new CaptureConnectionProperties(c.connectionId(),c.providerAccountRef(),c.mode(),c.apiOrigin()),
            clock,ticker);
        server.expect(requestTo(c.apiOrigin()+"/v2/payment_flows/"+expected.oldFlowId())).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        try{return gateway.observeOldAuthorization(expected);}finally{server.verify();}
    }
}
