package com.example.ecsite.support.restart;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

/** Private file IPC. Only fixed events/commands; never mail, credentials or keys. */
public final class RestartProbe {
    private final Path directory;
    public enum Stage { CONFIGURATION, CONTEXT_START, CONTEXT_VERIFY, WAIT_START, SCHEDULER_START, WAIT_STOP, STOP_PROCESSING, SCHEDULER_STOP, CONTEXT_CLOSE, NORMAL_EXIT }
    private static final Set<String> EVENTS=Set.of("READY","CLAIM_COMMITTED","SMTP_ENTERED","TICK","FAILED",
        "STOP_RECEIVED","STOP_PROCESSING","SCHEDULER_STOP_START","SCHEDULER_STOP_COMPLETE","SCHEDULER_STOP_EXCEPTION",
        "CONTEXT_CLOSE_START","CONTEXT_CLOSE_COMPLETE","LAUNCHER_NORMAL_EXIT","LAUNCHER_EXCEPTION_EXIT");
    public RestartProbe(Path directory) throws Exception {
        this.directory=directory.toRealPath();
        if(!Files.getPosixFilePermissions(this.directory).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IllegalStateException("Private probe directory required");
    }
    public void event(String event) { write(event, ""); }
    private void write(String event,String payload) {
        if(!EVENTS.contains(event))throw new IllegalArgumentException();
        try { Files.writeString(directory.resolve(event+"-"+String.format(Locale.ROOT,"%020d",System.nanoTime())+"-"+UUID.randomUUID()),payload,StandardOpenOption.CREATE_NEW); }
        catch(Exception e){throw new IllegalStateException("Probe event failed");}
    }
    /** No Throwable message, toString, full stack trace, filenames or request values. */
    public void exception(Stage stage,Throwable failure) {
        StringBuilder payload=new StringBuilder("stage=").append(stage.name()).append('\n');
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause=failure;
        for(int i=0;cause!=null && i<8 && seen.add(cause);i++,cause=cause.getCause()) {
            payload.append("type=").append(safeName(cause.getClass().getName())).append('\n');
            int limit=Math.min(6,cause.getStackTrace().length);
            for(int j=0;j<limit;j++) {
                var frame=cause.getStackTrace()[j];
                payload.append("frame=").append(safeName(frame.getClassName())).append('.').append(safeName(frame.getMethodName())).append(':').append(frame.getLineNumber()).append('\n');
            }
            for(Throwable suppressed:cause.getSuppressed())payload.append("suppressed=").append(safeName(suppressed.getClass().getName())).append('\n');
        }
        write(stage==Stage.SCHEDULER_STOP?"SCHEDULER_STOP_EXCEPTION":"LAUNCHER_EXCEPTION_EXIT",payload.toString());
    }
    private static String safeName(String name){return name.length()<=256 && name.matches("[A-Za-z0-9_.$<>]+")?name:"REDACTED_TYPE";}
    /** Parent emits only validated fixed events and safe class/method/line metadata. */
    public List<String> diagnostics() throws Exception {
        List<String> result=new ArrayList<>();
        try(var files=Files.list(directory)) {
            for(Path path:files.sorted(Comparator.comparing(p->p.getFileName().toString().substring(p.getFileName().toString().indexOf('-')+1))).toList()) {
                String event=path.getFileName().toString().split("-",2)[0];if(!EVENTS.contains(event))continue;
                result.add("event="+event);
                if(!event.equals("LAUNCHER_EXCEPTION_EXIT") && !event.equals("SCHEDULER_STOP_EXCEPTION"))continue;
                for(String line:Files.readAllLines(path)) {
                    if(line.startsWith("stage=") && Arrays.stream(Stage.values()).anyMatch(v->line.equals("stage="+v.name())))result.add(line);
                    else if(line.matches("(?:type|suppressed)=[A-Za-z0-9_.$<>]{1,256}"))result.add(line);
                    else if(line.matches("frame=[A-Za-z0-9_.$<>]{1,513}:-?[0-9]{1,10}"))result.add(line);
                }
            }
        }
        return List.copyOf(result);
    }
    public boolean allowed(String command){
        if(!Set.of("START","DELIVER","RELEASE_CLAIM","RELEASE_SMTP","STOP").contains(command))throw new IllegalArgumentException();
        return Files.isRegularFile(directory.resolve(command));
    }
    public void permit(String command) throws Exception { allowed(command);Files.writeString(directory.resolve(command),"",StandardOpenOption.CREATE_NEW); }
    public long count(String event) throws Exception {try(var files=Files.list(directory)){return files.filter(p->p.getFileName().toString().startsWith(event+"-")).count();}}
    public void awaitPermission(String command,Duration limit) throws Exception {
        long end=System.nanoTime()+limit.toNanos();
        while(!allowed(command)){if(System.nanoTime()>=end)throw new IllegalStateException("Probe permission timeout");Thread.sleep(20);}
    }
    public void awaitEvent(String event,long minimum,Process child,Duration limit) throws Exception {
        long end=System.nanoTime()+limit.toNanos();
        while(count(event)<minimum){
            if(!child.isAlive() || count("FAILED")>0 || System.nanoTime()>=end)throw new IllegalStateException("Child/probe failed or timed out");
            Thread.sleep(20);
        }
    }
}
