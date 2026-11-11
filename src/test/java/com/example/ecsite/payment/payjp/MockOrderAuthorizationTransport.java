package com.example.ecsite.payment.payjp;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.example.ecsite.config.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Runs the real raw-JSON observer against Mock HTTP only, for database isolation tests. */
public final class MockOrderAuthorizationTransport {
    private MockOrderAuthorizationTransport(){}
    public static OrderAuthorizationObservation observe(OrderAuthorizationObservation.Expected expected){
        return observe(expected,java.time.Clock.systemUTC(),System::nanoTime);
    }
    public static OrderAuthorizationObservation observe(OrderAuthorizationObservation.Expected expected,java.time.Clock clock,java.util.function.LongSupplier ticker){
        var body="""
            {"id":"%s","status":"requires_capture","amount":%d,"currency":"jpy","capture_method":"manual",
             "livemode":%s,"amount_received":null,"amount_capturable":%d,"metadata":{"order_id":"%s","order_change_operation_id":"%s"}}
            """.formatted(expected.flowId(),expected.amount(),expected.connection().mode().equals("LIVE"),expected.amount(),expected.orderId(),expected.authorizationOperationId());
        return observeRaw(expected,body,clock,ticker);
    }
    public static OrderAuthorizationObservation observeRaw(OrderAuthorizationObservation.Expected expected,String body,
            java.time.Clock clock,java.util.function.LongSupplier ticker){
        return observeRaw(expected,body,clock,ticker,200);
    }
    public static OrderAuthorizationObservation observeRaw(OrderAuthorizationObservation.Expected expected,String body,
            java.time.Clock clock,java.util.function.LongSupplier ticker,int httpStatus){
        var c=expected.connection();var builder=RestClient.builder().baseUrl(c.apiOrigin());
        var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new PayJpPaymentGateway(builder.build(),new PayJpAuthorizationMapper(),
            new PayJpProperties("pk_test_dummy","sk_test_dummy",c.apiOrigin(),"dummy"),
            new CaptureConnectionProperties(c.connectionId(),c.providerAccountRef(),c.mode(),c.apiOrigin()),
            clock,ticker);
        server.expect(requestTo(c.apiOrigin()+"/v2/payment_flows/"+expected.flowId())).andExpect(method(HttpMethod.GET))
            .andRespond(httpStatus<0?request->{throw new java.io.IOException("mock timeout");}:withStatus(HttpStatusCode.valueOf(httpStatus)).body(body).contentType(MediaType.APPLICATION_JSON));
        try{return gateway.observeOrderAuthorization(expected);}finally{server.verify();}
    }
}
