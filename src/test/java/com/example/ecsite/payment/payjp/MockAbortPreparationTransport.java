package com.example.ecsite.payment.payjp;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.example.ecsite.config.*;
import com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected;
import java.time.Clock;
import java.util.function.LongSupplier;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Real strict Gateway verification over one Mock GET; any POST or extra request fails. */
public final class MockAbortPreparationTransport {
    private MockAbortPreparationTransport(){}
    public static AbortPreparationObservation observe(Expected expected){
        var f=expected.flow();
        return observeRaw(expected,"""
            {"id":"%s","status":"requires_capture","amount":%d,"currency":"jpy","capture_method":"manual",
             "livemode":%s,"amount_received":0,"amount_capturable":%d,
             "metadata":{"order_id":"%s","order_change_operation_id":"%s"}}
            """.formatted(f.newFlowId(),f.amount(),f.connection().mode().equals("LIVE"),f.amount(),f.orderId(),f.operationId()),
            Clock.systemUTC(),System::nanoTime);
    }
    public static AbortPreparationObservation observeRaw(Expected expected,String body,Clock clock,LongSupplier ticker){
        var c=expected.flow().connection();var builder=RestClient.builder().baseUrl(c.apiOrigin());
        var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new PayJpPaymentGateway(builder.build(),new PayJpAuthorizationMapper(),
            new PayJpProperties("pk_test_dummy","sk_test_dummy",c.apiOrigin(),"dummy"),
            new CaptureConnectionProperties(c.connectionId(),c.providerAccountRef(),c.mode(),c.apiOrigin()),clock,ticker);
        server.expect(requestTo(c.apiOrigin()+"/v2/payment_flows/"+expected.flow().newFlowId()))
            .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        try{return gateway.observeAbortPreparation(expected);}finally{server.verify();}
    }
}
