package com.example.ecsite.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Missing keys disable handling operations, never fall back to a shared key. */
@Component
public class PaymentAuditNotificationHandlingToken {
    private final byte[] key;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentAuditNotificationHandlingToken(@Value("${PAYMENT_AUDIT_HANDLING_HMAC_KEY:}") String encoded) {
        this(encoded, Clock.systemUTC());
    }
    public PaymentAuditNotificationHandlingToken(String encoded, Clock clock) {
        this.clock=clock;
        if(encoded.isBlank()) { key=null; return; }
        try { key=Base64.getDecoder().decode(encoded); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException("Invalid handling signing key configuration"); }
        if(key.length<32) throw new IllegalArgumentException("Handling signing key must contain at least 32 bytes");
    }
    public record Snapshot(boolean handlingExists,long version,List<Integer> unknowns,List<Long> manualUnknowns,
        com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus handlingStatus) {
        public Snapshot(boolean exists,long version,List<Integer> unknowns){this(exists,version,unknowns,List.of(),com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.UNREVIEWED);}
        public Snapshot { unknowns=List.copyOf(unknowns); manualUnknowns=List.copyOf(manualUnknowns); }
    }
    public String issue(long notificationId,long administratorId,boolean exists,long version,Collection<Integer> unknowns) {
        return issue(notificationId,administratorId,exists,version,unknowns,List.of());
    }
    public String issue(long notificationId,long administratorId,boolean exists,long version,Collection<Integer> unknowns,Collection<Long> manualUnknowns) {
        return issue(notificationId,administratorId,exists,version,com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.UNREVIEWED,unknowns,manualUnknowns);
    }
    public String issue(long notificationId,long administratorId,boolean exists,long version,
        com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus status,Collection<Integer> unknowns,Collection<Long> manualUnknowns) {
        requireKey();
        if(status==null || !exists && status!=com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.UNREVIEWED) throw invalid();
        if(notificationId<=0 || administratorId<=0 || version<0 || !exists && version!=0) throw invalid();
        var sorted=new TreeSet<>(unknowns);
        if(sorted.stream().anyMatch(n->n<1 || n>10)) throw invalid();
        var manualSorted=new TreeSet<>(manualUnknowns);
        if(manualSorted.stream().anyMatch(n->n<1)) throw invalid();
        long issued=clock.instant().getEpochSecond();
        String numbers=String.join(",",sorted.stream().map(String::valueOf).toList());
        String manualNumbers=String.join(",",manualSorted.stream().map(String::valueOf).toList());
        String payload="handling-v2|"+notificationId+"|"+administratorId+"|"+exists+"|"+version+"|"+status.name()+"|"+numbers+"|"+manualNumbers+"|"+issued+"|"+(issued+900);
        byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
        return encode(bytes)+"."+encode(sign(bytes));
    }
    public Snapshot verify(String token,long notificationId,long administratorId) {
        requireKey();
        try {
            if(token==null || token.length()>2048) throw invalid();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=2) throw invalid();
            byte[] payload=Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature=Base64.getUrlDecoder().decode(parts[1]);
            if(!encode(payload).equals(parts[0]) || !encode(signature).equals(parts[1]) || !MessageDigest.isEqual(sign(payload),signature)) throw invalid();
            String[] f=new String(payload,StandardCharsets.UTF_8).split("\\|",-1);
            if(f.length!=10 || !f[0].equals("handling-v2") || Long.parseLong(f[1])!=notificationId || Long.parseLong(f[2])!=administratorId
                || !Set.of("true","false").contains(f[3])) throw invalid();
            boolean exists=Boolean.parseBoolean(f[3]); long version=Long.parseLong(f[4]);
            long issued=Long.parseLong(f[8]), expires=Long.parseLong(f[9]), now=clock.instant().getEpochSecond();
            if(version<0 || !exists && version!=0 || issued>now || expires-issued!=900 || now>=expires) throw invalid();
            List<Integer> numbers=f[6].isEmpty()?List.of():Arrays.stream(f[6].split(",",-1)).map(Integer::valueOf).toList();
            if(numbers.stream().anyMatch(n->n<1 || n>10) || !numbers.equals(new ArrayList<>(new TreeSet<>(numbers)))) throw invalid();
            var status=com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.valueOf(f[5]);
            if(!exists && status!=com.example.ecsite.entity.PaymentAuditNotificationHandlingStatus.UNREVIEWED) throw invalid();
            List<Long> manualNumbers=f[7].isEmpty()?List.of():Arrays.stream(f[7].split(",",-1)).map(Long::valueOf).toList();
            if(manualNumbers.stream().anyMatch(n->n<1) || !manualNumbers.equals(new ArrayList<>(new TreeSet<>(manualNumbers)))) throw invalid();
            return new Snapshot(exists,version,numbers,manualNumbers,status);
        } catch(RuntimeException e) { throw invalid(); }
    }
    public boolean isConfigured() { return key != null; }
    private void requireKey() { if(key==null) throw new IllegalStateException("Handling signing key is not configured"); }
    private byte[] sign(byte[] payload) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(payload); }
        catch(java.security.GeneralSecurityException e) { throw new IllegalStateException("Handling signature unavailable"); }
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static class InvalidSnapshotException extends IllegalArgumentException {
        private InvalidSnapshotException() { super("Invalid or expired handling snapshot; reload the page"); }
    }
    private static IllegalArgumentException invalid() { return new InvalidSnapshotException(); }
}
