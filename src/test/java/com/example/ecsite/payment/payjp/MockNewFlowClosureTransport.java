package com.example.ecsite.payment.payjp;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.example.ecsite.config.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Real Gateway/A1 verification over Mock GET; any POST or additional request fails. */
public final class MockNewFlowClosureTransport {
    private MockNewFlowClosureTransport(){}
    public static NewFlowClosureObservation observe(com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected expected){
        return observe(expected,java.time.Clock.systemUTC(),System::nanoTime);
    }
    public static NewFlowClosureObservation observe(com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected expected,
            java.time.Clock clock,java.util.function.LongSupplier ticker){
        return assess(expected,"canceled",200,clock,ticker).closure().orElseThrow();
    }
    public static NewFlowClosureObservation.Assessment assess(com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected expected,
            String status,int httpStatus,java.time.Clock clock,java.util.function.LongSupplier ticker){
        var c=expected.flow().connection();var builder=RestClient.builder().baseUrl(c.apiOrigin());
        var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new PayJpPaymentGateway(builder.build(),new PayJpAuthorizationMapper(),
            new PayJpProperties("pk_test_dummy","sk_test_dummy",c.apiOrigin(),"dummy"),
            new CaptureConnectionProperties(c.connectionId(),c.providerAccountRef(),c.mode(),c.apiOrigin()),
            clock,ticker);
        var f=expected.flow();
        var request=server.expect(requestTo(c.apiOrigin()+"/v2/payment_flows/"+f.newFlowId())).andExpect(method(HttpMethod.GET));
        if(httpStatus!=200)request.andRespond(withStatus(HttpStatus.valueOf(httpStatus)));
        else request.andRespond(withSuccess("""
                {"id":"%s","status":"%s","amount":%d,"currency":"jpy","capture_method":"manual",
                 "livemode":false,"amount_received":0,"amount_capturable":0,
                 "metadata":{"order_id":"%s","order_change_operation_id":"%s"}}
                """.formatted(f.newFlowId(),status,f.amount(),f.orderId(),f.operationId()),MediaType.APPLICATION_JSON));
        try{return gateway.assessNewFlowClosure(expected);}finally{server.verify();}
    }
}
