package com.example.ecsite.service.payment.safety;

import java.time.ZoneId;
import java.util.*;
import com.example.ecsite.payment.capture.*;
import com.example.ecsite.service.payment.PaymentSafetySnapshot.CaptureVerification;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.*;
import static com.example.ecsite.service.payment.PaymentSafetySnapshot.CaptureVerification.*;
import static com.example.ecsite.service.payment.safety.CaptureEvidenceValidation.*;

/** Pure verifier of detached data. No Store, Entity, Gateway, environment, clock or recovery entry.
 * Expected malformed persisted values become MISMATCH; unexpected failures propagate. A validated
 * request is discarded immediately: VERIFIED can never reconstruct initial POST eligibility.
 */
public final class CaptureRequestEvidenceVerifier {
    public CaptureEvidenceValidation verify(PaymentSafetyReadFacts facts,CaptureEvidenceProfiles profiles) {
        Objects.requireNonNull(facts);Objects.requireNonNull(profiles);
        var assessments=new ArrayList<CaptureAssessment>();var global=new ArrayList<Finding>();
        for(var t:facts.transactions()) if("CAPTURE".equals(t.transactionType())) assessments.add(assess(facts,t,profiles));
        for(var r:facts.captureRequests()) {
            long targets=facts.transactions().stream().filter(t->"CAPTURE".equals(t.transactionType()) && Objects.equals(t.id(),r.captureTransactionId())).count();
            if(targets!=1)global.add(new Finding(Code.ORPHAN_REQUEST,r.paymentId(),r.captureTransactionId(),r.id(),"Stored request has no unique CAPTURE target"));
        }
        return new CaptureEvidenceValidation(profiles,assessments,global);
    }
    private CaptureAssessment assess(PaymentSafetyReadFacts f,TransactionRow t,CaptureEvidenceProfiles profiles) {
        var requests=f.captureRequests().stream().filter(r->Objects.equals(r.captureTransactionId(),t.id())).toList();
        var findings=new ArrayList<Finding>();
        if(requests.isEmpty()) {
            add(findings,Code.REQUEST_MISSING,t,null,"No persisted request supplied for CAPTURE");
            return new CaptureAssessment(t,requests,MISSING,findings);
        }
        if(requests.size()!=1) {
            add(findings,Code.REQUEST_MULTIPLE,t,null,"Multiple persisted requests; none selected");
            return new CaptureAssessment(t,requests,MISMATCH,findings);
        }
        var r=requests.getFirst();var order=f.order();
        var payments=f.payments().stream().filter(p->Objects.equals(p.id(),t.paymentId())).toList();
        if(!positive(t.id()) || !positive(r.id())
                || f.transactions().stream().filter(x->Objects.equals(x.id(),t.id())).count()!=1
                || f.transactions().stream().filter(x->Objects.equals(x.paymentId(),t.paymentId()) && "CAPTURE".equals(x.transactionType())).count()!=1
                || f.captureRequests().stream().filter(x->Objects.equals(x.id(),r.id())).count()!=1) {
            add(findings,Code.DUPLICATE_ID,t,r,"Invalid or ambiguous operation/request identity");
        }
        PaymentRow p=payments.size()==1?payments.getFirst():null;
        if(p==null || !positive(order.id()) || !positive(p.id()) || !Objects.equals(p.orderId(),order.id())
                || !"SHIPPED".equals(order.status()) || !Objects.equals(order.shipmentCapturePaymentId(),t.paymentId())
                || !Objects.equals(r.paymentId(),t.paymentId()) || !"CAPTURE".equals(r.transactionType())
                || !"PAYJP".equals(p.provider()) || !"CARD".equals(p.paymentMethod())
                || !"PAYJP".equals(r.provider()) || !"CARD".equals(r.paymentMethod())
                || !Objects.equals(p.providerPaymentId(),r.providerFlowId())
                || (t.providerTransactionId()!=null && !Objects.equals(t.providerTransactionId(),r.providerFlowId()))
                || !positive(t.amount()) || !Objects.equals(t.amount(),order.totalAmount()) || !Objects.equals(t.amount(),r.amountToCapture())
                || t.orderContentRevision()==null || t.orderContentRevision()<0
                || !Objects.equals(t.orderContentRevision(),order.contentRevision()) || !Objects.equals(t.orderContentRevision(),r.orderContentRevision())
                || t.idempotencyKey()==null || !Objects.equals(t.idempotencyKey(),r.idempotencyKey())) {
            add(findings,Code.TARGET_MISMATCH,t,r,"Order/Payment/CAPTURE/request identity, amount or revision mismatch");
        }
        // Payment.amount is the original authorization amount; no equality with captured amount.
        CaptureConnectionIdentity storedIdentity=null;
        if(r.amountToCapture()==null || r.bodyFormatVersion()==null || r.fingerprintVersion()==null
                || r.requestBody()==null || r.requestFingerprint()==null) {
            add(findings,Code.HTTP_INTEGRITY_MISMATCH,t,r,"Missing persisted HTTP fields");
        } else {
            try {
                storedIdentity=new CaptureConnectionIdentity(r.connectionId(),r.providerAccountRef(),r.mode(),r.apiOrigin());
                var restored=CaptureHttpRequest.restore(storedIdentity,r.providerFlowId(),r.amountToCapture(),r.idempotencyKey(),
                    r.httpMethod(),r.apiPath(),r.contentType(),r.bodyFormatVersion(),r.requestBody().value(),r.fingerprintVersion(),r.requestFingerprint().value());
                if(r.id()==null || r.paymentId()==null || r.captureTransactionId()==null || r.orderContentRevision()==null) {
                    add(findings,Code.TIME_METADATA_MISMATCH,t,r,"Missing immutable request metadata");
                } else {
                    try { new StoredCaptureRequest(r.id(),r.paymentId(),r.captureTransactionId(),r.orderContentRevision(),restored,r.createdAt(),r.replayNotAfter()); }
                    catch(IllegalArgumentException invalidMetadata) { add(findings,Code.TIME_METADATA_MISMATCH,t,r,"Invalid precision, identifiers or saved replay interval"); }
                }
            } catch(IllegalArgumentException invalidHttp) { add(findings,Code.HTTP_INTEGRITY_MISMATCH,t,r,"Persisted HTTP bytes, fingerprint or identity invalid"); }
        }
        var manifest=profiles.connectionManifest();
        var expected=manifest==null || t.id()==null?null:manifest.identitiesByTransaction().get(t.id());
        if(expected==null)add(findings,Code.CONNECTION_APPROVAL_MISSING,t,r,"No approved historical connection for this transaction");
        else if(storedIdentity!=null && !storedIdentity.equals(expected))add(findings,Code.CONNECTION_MISMATCH,t,r,"Approved historical connection differs from stored identity");
        var time=profiles.timestampBinding();String zone=time==null || t.id()==null?null:time.localZonesByTransaction().get(t.id());
        if(zone==null)add(findings,Code.TIME_PROFILE_MISSING,t,r,"No approved local/instant binding profile for this transaction");
        else if(time.encodingVersion()!=1)add(findings,Code.TIME_PROFILE_UNSUPPORTED,t,r,"Unsupported historical timestamp encoding version");
        else if(t.createdAt()==null || order.shippedAt()==null || r.createdAt()==null) {
            add(findings,Code.TIME_BINDING_MISMATCH,t,r,"Missing required V54 creation/shipment timestamp");
        } else {
            var offsets=ZoneId.of(zone).getRules().getValidOffsets(t.createdAt());
            if(!t.createdAt().equals(order.shippedAt()))
                add(findings,Code.TIME_BINDING_MISMATCH,t,r,"Capture and shipment local values contradict approved shared-value encoding");
            if(offsets.size()!=1)add(findings,Code.TIME_BINDING_UNPROVEN,t,r,"Local time has no unique instant in approved zone");
            else if(!t.createdAt().toInstant(offsets.getFirst()).equals(r.createdAt()))
                add(findings,Code.TIME_BINDING_MISMATCH,t,r,"Saved local/instant binding contradicts approved Feature123 encoding");
        }
        boolean mismatch=findings.stream().anyMatch(x->switch(x.code()) {
            case CONNECTION_APPROVAL_MISSING,TIME_PROFILE_MISSING,TIME_PROFILE_UNSUPPORTED,TIME_BINDING_UNPROVEN -> false;
            default -> true;
        });
        return new CaptureAssessment(t,requests,mismatch?MISMATCH:findings.isEmpty()?VERIFIED:UNVERIFIED,findings);
    }
    private static boolean positive(Number n) {return n!=null && n.longValue()>0;}
    private static void add(List<Finding> findings,Code code,TransactionRow t,CaptureRow r,String detail) {
        findings.add(new Finding(code,t.paymentId(),t.id(),r==null?null:r.id(),detail));
    }
}
