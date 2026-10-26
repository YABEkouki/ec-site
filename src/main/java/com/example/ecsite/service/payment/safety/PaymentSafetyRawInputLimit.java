package com.example.ecsite.service.payment.safety;

import java.lang.reflect.*;
import java.util.*;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.*;

/** Shared reader-format budget. Preflight avoids allocating/sorting an already oversized row set.
 * Only size checks are performed here; the existing fingerprint encoder remains authoritative. */
public final class PaymentSafetyRawInputLimit {
    private static final long MAX=16L*1024*1024;
    private PaymentSafetyRawInputLimit() {}
    public static void requireWithinReaderLimit(PaymentSafetyReadFacts facts) {
        Objects.requireNonNull(facts);
        // Payload byte lower bound: check before UTF-8/Base64 copies or the multiset row buffers.
        scalarBudget(facts,new long[]{0});
        long size=checked(0,PaymentSafetyInputFingerprint.encodedSize(facts.order()));
        for(var rows:List.of(facts.payments(),facts.transactions(),facts.statusHistory(),facts.captureRequests()))
            for(var row:rows)size=checked(size,PaymentSafetyInputFingerprint.encodedSize(row));
        for(var map:List.of(facts.verificationProfiles(),facts.timeOrigins()))for(var entry:map.entrySet()) {
            size=checked(size,PaymentSafetyInputFingerprint.encodedSize(entry.getKey()));
            size=checked(size,PaymentSafetyInputFingerprint.encodedSize(entry.getValue()));
        }
        // Includes the exact container, record and type/name framing. No new canonical encoding.
        checked(0,PaymentSafetyInputFingerprint.encodedSize(facts));
    }
    private static long checked(long count,long add) {
        if(add>MAX-count)throw invalid();return count+add;
    }
    private static void scalarBudget(Object value,long[] size) {
        if(value==null)return;
        if(value instanceof Bytes b){size[0]=checked(size[0],b.length());}
        else if(value instanceof String s) {
            for(int i=0;i<s.length();i++) {
                char c=s.charAt(i);int bytes;
                if(c==0)throw invalid();
                if(c<128)bytes=1;else if(c<2048)bytes=2;
                else if(Character.isHighSurrogate(c)){if(++i>=s.length() || !Character.isLowSurrogate(s.charAt(i)))throw invalid();bytes=4;}
                else if(Character.isLowSurrogate(c))throw invalid();else bytes=3;
                size[0]=checked(size[0],bytes);
            }
        } else if(value instanceof List<?> list)for(var item:list)scalarBudget(item,size);
        else if(value instanceof Map<?,?> map)for(var entry:map.entrySet()){scalarBudget(entry.getKey(),size);scalarBudget(entry.getValue(),size);}
        else if(value.getClass().isRecord())for(var component:value.getClass().getRecordComponents()) {
            try{scalarBudget(component.getAccessor().invoke(value),size);}catch(ReflectiveOperationException e){throw invalid();}
        }
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid payment safety payload");}
}
