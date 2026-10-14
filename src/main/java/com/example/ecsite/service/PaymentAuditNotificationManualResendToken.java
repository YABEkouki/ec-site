package com.example.ecsite.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Dedicated key and purpose; never logs keys or tokens and has no default signing key. */
@Component
public class PaymentAuditNotificationManualResendToken {
    private static final String PURPOSE="manual-resend-v1";
    private final byte[] key;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentAuditNotificationManualResendToken(@Value("${PAYMENT_AUDIT_MANUAL_RESEND_HMAC_KEY:}") String encoded){this(encoded,Clock.systemUTC());}
    public PaymentAuditNotificationManualResendToken(String encoded,Clock clock){
        this.clock=Objects.requireNonNull(clock);
        if(encoded.isBlank()){key=null;return;}
        try{key=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid manual resend signing key configuration");}
        if(key.length<32)throw new IllegalArgumentException("Manual resend signing key must contain at least 32 bytes");
    }
    public record Snapshot(UUID nonce,String generation,boolean handlingExists,long handlingVersion) {}
    public boolean isConfigured(){return key!=null;}
    public String issue(long notificationId,long actorId,UUID nonce,String generation,boolean exists,long version){
        requireKey();
        if(notificationId<=0 || actorId<=0 || nonce==null || generation==null || !generation.matches("[A-Za-z0-9_-]{1,128}") || version<0 || !exists && version!=0)throw invalid();
        long issued=clock.instant().getEpochSecond();
        byte[] payload=(PURPOSE+"|"+notificationId+"|"+actorId+"|"+nonce+"|"+generation+"|"+exists+"|"+version+"|"+issued+"|"+(issued+900)).getBytes(StandardCharsets.UTF_8);
        return encode(payload)+"."+encode(sign(payload));
    }
    public Snapshot verify(String token,long notificationId,long actorId){
        requireKey();
        try{
            if(token==null || token.length()>2048 || notificationId<=0 || actorId<=0)throw invalid();
            String[] p=token.split("\\.",-1); if(p.length!=2)throw invalid();
            byte[] payload=Base64.getUrlDecoder().decode(p[0]), signature=Base64.getUrlDecoder().decode(p[1]);
            if(!encode(payload).equals(p[0]) || !encode(signature).equals(p[1]) || !MessageDigest.isEqual(sign(payload),signature))throw invalid();
            String[] f=new String(payload,StandardCharsets.UTF_8).split("\\|",-1);
            if(f.length!=9 || !PURPOSE.equals(f[0]) || Long.parseLong(f[1])!=notificationId || Long.parseLong(f[2])!=actorId || !Set.of("true","false").contains(f[5]))throw invalid();
            UUID nonce=UUID.fromString(f[3]); boolean exists=Boolean.parseBoolean(f[5]); long version=Long.parseLong(f[6]);
            long issued=Long.parseLong(f[7]),expires=Long.parseLong(f[8]),now=clock.instant().getEpochSecond();
            if(!nonce.toString().equals(f[3]) || !f[4].matches("[A-Za-z0-9_-]{1,128}") || version<0 || !exists && version!=0 || issued>now || expires<=issued || expires-issued!=900 || now>=expires)throw invalid();
            return new Snapshot(nonce,f[4],exists,version);
        }catch(RuntimeException failure){throw invalid();}
    }
    private void requireKey(){if(key==null)throw new IllegalStateException("Manual resend signing key is not configured");}
    private byte[] sign(byte[] payload){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(payload);}catch(java.security.GeneralSecurityException failure){throw new IllegalStateException("Manual resend signature unavailable");}}
    private static String encode(byte[] data){return Base64.getUrlEncoder().withoutPadding().encodeToString(data);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid or expired manual resend snapshot; reload the page");}
}
