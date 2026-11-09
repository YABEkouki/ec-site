package com.example.ecsite.support.display;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only in-memory responses; deliberately has no proxy/client/controller dependency. */
public final class DisplayHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final Map<String,DisplayPage> pages;
    private final AtomicInteger posts=new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    public DisplayHttpServer(List<DisplayPage> input)throws IOException {
        var copy=new LinkedHashMap<String,DisplayPage>();
        for(var p:input)if(copy.put(p.path(),p)!=null)throw new IllegalArgumentException("Duplicate display route");
        pages=Map.copyOf(copy);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                String method=exchange.getRequestMethod(),path=exchange.getRequestURI().toString();
                if(method.equals("POST"))posts.incrementAndGet();
                if(!method.equals("GET")&&!method.equals("HEAD")){
                    exchange.getResponseHeaders().set("Allow","GET, HEAD");exchange.sendResponseHeaders(405,-1);return;
                }
                if(!DisplayPage.safePath(path)){exchange.sendResponseHeaders(400,-1);return;}
                var p=pages.get(path);if(p==null){exchange.sendResponseHeaders(404,-1);return;}
                p.headers().forEach((k,v)->exchange.getResponseHeaders().set(k,v));
                if(method.equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",Integer.toString(p.body().length));exchange.sendResponseHeaders(200,-1);return;}
                byte[] body=p.body();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            } finally {exchange.close();}
        });
        server.start();
    }
    public String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public int receivedPosts(){return posts.get();}
    /** This server only serves preloaded bytes; it cannot release or forward any requests. */
    public int forwardedRequests(){return 0;}
    @Override public void close(){if(closed.compareAndSet(false,true))server.stop(0);}
}
