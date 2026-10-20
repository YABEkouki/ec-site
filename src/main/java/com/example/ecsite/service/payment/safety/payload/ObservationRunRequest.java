package com.example.ecsite.service.payment.safety.payload;

import com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeOrigin;
import com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeReference;
import com.example.ecsite.service.payment.safety.CaptureEvidenceProfiles;
import com.example.ecsite.service.payment.safety.PaymentSafetyInputFingerprint;
import java.util.*;

/** Frozen OBSERVE_ONLY intent. No worker authority, DB lookup, target discovery or execution.
 * requestId is the V56 run id, not a second database identifier. Scope describes the selection
 * boundary; only the supplied manifest IDs are targets, including an explicitly missing SINGLE.
 */
public record ObservationRunRequest(UUID requestId, ExecutionType executionType, Manifest manifest, Actor requester) {
    public ObservationRunRequest {
        if(requestId==null || executionType!=ExecutionType.DIAGNOSTIC || manifest==null || requester==null)throw PayloadEncodingV1.invalid();
    }
    public enum ExecutionType { DIAGNOSTIC }
    public String mode(){return "OBSERVE_ONLY";}
    public String guardActivationRef(){return null;}
    public String financialResumption(){return "NOT_EVALUATED";}
    @Override public String toString(){return "ObservationRunRequest[redacted]";}

    public enum ScopeKind { RANGE, SINGLE }
    public record Scope(ScopeKind kind,long lowerOrderIdExclusive,long upperOrderIdInclusive,Long targetOrderId) {
        public Scope {
            if(kind==null || lowerOrderIdExclusive<0 || lowerOrderIdExclusive>upperOrderIdInclusive
                || (kind==ScopeKind.RANGE && targetOrderId!=null)
                || (kind==ScopeKind.SINGLE && (targetOrderId==null || targetOrderId<=0
                    || lowerOrderIdExclusive!=targetOrderId-1 || upperOrderIdInclusive!=targetOrderId)))throw PayloadEncodingV1.invalid();
        }
        public static Scope range(long lower,long upper){return new Scope(ScopeKind.RANGE,lower,upper,null);}
        public static Scope single(long id){if(id<=0)throw PayloadEncodingV1.invalid();return new Scope(ScopeKind.SINGLE,id-1,id,id);}
    }
    public enum ActorType { SYSTEM, ADMIN }
    public record Actor(ActorType type,Long accountId,String username) {
        public Actor {
            text(username,100);
            if(type==null || (type==ActorType.SYSTEM && accountId!=null) || (type==ActorType.ADMIN && (accountId==null || accountId<=0)))throw PayloadEncodingV1.invalid();
        }
        public static Actor system(String name){return new Actor(ActorType.SYSTEM,null,name);}
        public static Actor admin(long id,String name){return new Actor(ActorType.ADMIN,id,name);}
        @Override public String toString(){return "ObservationActor[redacted]";}
    }
    /** maxAttempts includes the first attempt. Values describe policy, never acquire/renew a lease. */
    public record Policy(int leaseSeconds,int heartbeatSeconds,int maxAttempts,int lockTimeoutMillis,
            int statementTimeoutSeconds,int transactionTimeoutSeconds,int maxOrders) {
        public Policy {
            if(leaseSeconds<=0 || leaseSeconds>86400 || heartbeatSeconds<=0 || heartbeatSeconds>=leaseSeconds
                || maxAttempts<1 || maxAttempts>100 || lockTimeoutMillis<1
                || statementTimeoutSeconds<1 || (long)lockTimeoutMillis>=1000L*statementTimeoutSeconds
                || transactionTimeoutSeconds<=statementTimeoutSeconds || transactionTimeoutSeconds>=leaseSeconds
                || maxOrders<1 || maxOrders>1000)throw PayloadEncodingV1.invalid();
        }
        public static Policy defaults(){return new Policy(120,20,3,1000,10,30,1000);}
    }
    /** Version is the enclosing codec's explicit V1. All maps/approval contents are detached.
     * Approval inputs must be non-secret; neither these records nor a digest authenticate them.
     * The codec enforces transport/JSONB capacity before a manifest can be persisted.
     */
    public record Manifest(Scope scope,List<Long> targetOrderIds,String classificationContractVersion,
            String classifierImplVersion,short fingerprintFormatVersion,CaptureEvidenceProfiles captureProfiles,
            Map<String,String> verificationProfiles,Map<TimeReference,TimeOrigin> timeOrigins,Policy policy) {
        public Manifest {
            if(scope==null || targetOrderIds==null || captureProfiles==null || verificationProfiles==null || timeOrigins==null || policy==null
                || targetOrderIds.size()>policy.maxOrders() || fingerprintFormatVersion!=PaymentSafetyInputFingerprint.FORMAT_VERSION)throw PayloadEncodingV1.invalid();
            // Validate the detached copy, not a caller-owned collection that may change while copied.
            try {
                targetOrderIds=List.copyOf(targetOrderIds);
                verificationProfiles=Map.copyOf(verificationProfiles);timeOrigins=Map.copyOf(timeOrigins);
            }catch(RuntimeException malformed){throw PayloadEncodingV1.invalid();}
            if(targetOrderIds.size()>policy.maxOrders())throw PayloadEncodingV1.invalid();
            text(classificationContractVersion,64);text(classifierImplVersion,128);
            long previous=scope.lowerOrderIdExclusive();
            for(Long id:targetOrderIds){if(id==null || id<=previous || id>scope.upperOrderIdInclusive())throw PayloadEncodingV1.invalid();previous=id;}
            if(scope.kind()==ScopeKind.SINGLE && !targetOrderIds.equals(List.of(scope.targetOrderId())))throw PayloadEncodingV1.invalid();
            verificationProfiles.forEach((k,v)->{if(k==null || v==null)throw PayloadEncodingV1.invalid();});
            timeOrigins.forEach((k,v)->{if(k==null || v==null)throw PayloadEncodingV1.invalid();});
        }
        @Override public String toString(){return "ObservationManifest[redacted]";}
    }
    /** PostgreSQL varchar character length and btrim's ASCII-space blank check. No normalization. */
    static void text(String value,int maxCodePoints) {
        if(value==null || value.codePointCount(0,value.length())>maxCodePoints || value.chars().allMatch(c->c==' '))throw PayloadEncodingV1.invalid();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);if(c==0)throw PayloadEncodingV1.invalid();
            if(Character.isHighSurrogate(c)){if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i)))throw PayloadEncodingV1.invalid();}
            else if(Character.isLowSurrogate(c))throw PayloadEncodingV1.invalid();
        }
    }
}
