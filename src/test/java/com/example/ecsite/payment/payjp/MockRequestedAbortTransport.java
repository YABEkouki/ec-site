package com.example.ecsite.payment.payjp;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.example.ecsite.config.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Runs the real raw-JSON observer against Mock HTTP only, for database isolation tests. */
public final class MockRequestedAbortTransport {
    private MockRequestedAbortTransport(){}
    public static RequestedAbortObservation observe(RequestedAbortObservation.Expected expected){
        return observe(expected,java.time.Clock.systemUTC(),System::nanoTime);
    }
    public static RequestedAbortObservation observe(RequestedAbortObservation.Expected expected,java.time.Clock clock,java.util.function.LongSupplier ticker){
        var c=expected.flow().connection();var builder=RestClient.builder().baseUrl(c.apiOrigin());
        var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new PayJpPaymentGateway(builder.build(),new PayJpAuthorizationMapper(),
            new PayJpProperties("pk_test_dummy","sk_test_dummy",c.apiOrigin(),"dummy"),
            new CaptureConnectionProperties(c.connectionId(),c.providerAccountRef(),c.mode(),c.apiOrigin()),
            clock,ticker);
        var f=expected.flow();
        server.expect(requestTo(c.apiOrigin()+"/v2/payment_flows/"+f.newFlowId())).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"id":"%s","status":"requires_capture","amount":%d,"currency":"jpy","capture_method":"manual",
                 "livemode":false,"amount_received":null,"amount_capturable":%d,
                 "metadata":{"order_id":"%s","order_change_operation_id":"%s"}}
                """.formatted(f.newFlowId(),f.amount(),f.amount(),f.orderId(),f.operationId()),MediaType.APPLICATION_JSON));
        var result=gateway.observeRequestedAbort(expected);server.verify();return result;
    }
    /** Max one fake POST; the production token, body bytes and key are used unchanged. */
    public static void send(com.example.ecsite.service.orderchange.RequestedAbortDispatchControl.Dispatch dispatch,
            com.example.ecsite.payment.orderchange.StoredOrderChangeRequest saved,int status,
            java.util.concurrent.atomic.AtomicInteger posts,Runnable duringPost){
        var request=saved.httpRequest();var c=request.connection();var builder=RestClient.builder().baseUrl(c.apiOrigin());
        var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new PayJpPaymentGateway(builder.build(),new PayJpAuthorizationMapper(),
            new PayJpProperties("pk_test_dummy","sk_test_dummy",c.apiOrigin(),"dummy"),
            new CaptureConnectionProperties(c.connectionId(),c.providerAccountRef(),c.mode(),c.apiOrigin()),
            java.time.Clock.systemUTC(),System::nanoTime);
        server.expect(org.springframework.test.web.client.ExpectedCount.between(0,1),requestTo(c.apiOrigin()+request.path()))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key",request.idempotencyKey()))
            .andExpect(content().bytes(request.body())).andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andRespond(http->{
                posts.incrementAndGet();duringPost.run();
                if(status==0)throw new java.net.SocketTimeoutException("Mock timeout");
                if(status==-1)throw new java.net.SocketException("Mock disconnect");
                return withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"status\":\"canceled\",\"amount_received\":null}").createResponse(http);
            });
        try{gateway.sendRequestedAbort(dispatch);}finally{server.verify();}
    }

}
