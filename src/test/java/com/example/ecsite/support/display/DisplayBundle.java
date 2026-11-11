package com.example.ecsite.support.display;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Private generated files only. Used from an isolated test copy, never production startup. */
public final class DisplayBundle {
    private DisplayBundle(){}
    public static void write(Path output,List<DisplayPage> pages)throws Exception {
        Path root=output.toAbsolutePath().normalize();
        if(!root.startsWith(Path.of("/tmp"))||root.equals(Path.of("/tmp")))throw new IllegalArgumentException("Display output must be private /tmp directory");
        Files.createDirectories(root);Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        var mapper=JsonMapper.builder().build();Path file=root.resolve("pages.json");
        Files.writeString(file,mapper.writeValueAsString(pages));Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        for(var page:pages)if(page.headers().getOrDefault("Content-Type","").startsWith("text/html")){
            Path html=root.resolve(page.name()+".html");Files.write(html,page.body());Files.setPosixFilePermissions(html,PosixFilePermissions.fromString("rw-------"));
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Specify the generated pages.json file");
        var pages=JsonMapper.builder().build().readValue(Files.readString(Path.of(args[0])),DisplayPage[].class);
        try(var server=new DisplayHttpServer(Arrays.asList(pages))){
            Runtime.getRuntime().addShutdownHook(new Thread(()->{System.out.println("Display server received POSTs="+server.receivedPosts()+", forwarded requests="+server.forwardedRequests());server.close();},"display-only-cleanup"));
            System.out.println("Generated manifest: "+Path.of(args[0]).toAbsolutePath());
            System.out.println("DISPLAY ONLY: "+server.origin()+" (synthetic authenticated HTML; no real Widget/DB). Ctrl-C to stop.");
            for(var p:pages)if(p.headers().getOrDefault("Content-Type","").startsWith("text/html"))System.out.println(p.name()+" "+server.origin()+p.path());
            new java.util.concurrent.CountDownLatch(1).await();
        }
    }
}
