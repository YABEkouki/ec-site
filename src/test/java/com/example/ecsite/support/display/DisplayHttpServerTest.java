package com.example.ecsite.support.display;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DisplayHttpServerTest {
    private static final DisplayPage PAGE=new DisplayPage("fixture", "/orders/900001/edit",Map.of("Content-Type","text/html;charset=UTF-8","Content-Security-Policy","default-src 'none'; style-src 'self'","Cache-Control","no-store"),"<main>fixture</main>".getBytes(StandardCharsets.UTF_8));
    HttpClient client=HttpClient.newHttpClient();
    HttpResponse<byte[]> request(DisplayHttpServer s,String path,String method)throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(s.origin()+path)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void servesOnlyRecordedResponsesAndPreservesHeaders()throws Exception {
        try(var s=new DisplayHttpServer(List.of(PAGE))){
            assertThat(s.origin()).startsWith("http://127.0.0.1:").doesNotContain(":8080");
            var r=request(s,PAGE.path(),"GET");assertThat(r.statusCode()).isEqualTo(200);assertThat(r.body()).isEqualTo(PAGE.body());
            assertThat(r.headers().firstValue("Content-Security-Policy")).contains(PAGE.headers().get("Content-Security-Policy"));
            assertThat(request(s,PAGE.path(),"HEAD").body()).isEmpty();
        }
    }
    @Test void deniesEveryNonReadMethodWithoutInvokingAnything()throws Exception {
        try(var s=new DisplayHttpServer(List.of(PAGE))){
            for(String method:List.of("POST","PUT","PATCH","DELETE","OPTIONS"))assertThat(request(s,PAGE.path(),method).statusCode()).isEqualTo(405);
            assertThat(s.receivedPosts()).isEqualTo(1);assertThat(s.forwardedRequests()).isZero();
        }
    }
    @Test void rejectsUnregisteredTraversalAndEncodedSeparators()throws Exception {
        try(var s=new DisplayHttpServer(List.of(PAGE))){
            for(String path:List.of("/unknown","/../pom.xml","/%2e%2e/pom.xml","/orders%2f900001/edit","/orders/900001/edit?other=1"))
                assertThat(request(s,path,"GET").statusCode()).isIn(400,404);
        }
    }
    @Test void closeActuallyReleasesTheSocket()throws Exception {
        var s=new DisplayHttpServer(List.of(PAGE));String origin=s.origin();s.close();s.close();
        assertThatThrownBy(()->client.send(HttpRequest.newBuilder(URI.create(origin+PAGE.path())).build(),HttpResponse.BodyHandlers.discarding())).isInstanceOf(java.io.IOException.class);
    }
    @Test void refusesUnsafeRegistrationAndDefensivelyCopiesBodies(){
        assertThatThrownBy(()->new DisplayPage("bad","/../secret",Map.of(),new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new DisplayPage("bad","https://api.pay.jp",Map.of(),new byte[0])).isInstanceOf(IllegalArgumentException.class);
        byte[] body=PAGE.body();body[0]=0;assertThat(PAGE.body()[0]).isEqualTo((byte)'<');
    }
    @Test void proxyLookingRequestsNeverReachAnOutboundSentinel()throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var sentinel=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        sentinel.createContext("/",e->{calls.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});sentinel.start();
        try(var s=new DisplayHttpServer(List.of(PAGE))){
            String target="http://127.0.0.1:"+sentinel.getAddress().getPort()+"/financial";
            assertThat(request(s,"/proxy?url="+target,"GET").statusCode()).isEqualTo(404);
            assertThat(request(s,"/"+target,"GET").statusCode()).isEqualTo(404);
            assertThat(calls.get()).isZero();
        } finally {sentinel.stop(0);}
    }
}
