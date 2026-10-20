package com.example.ecsite.service.payment.safety.payload;

import com.example.ecsite.service.payment.PaymentSafetyClassification;
import com.example.ecsite.service.payment.safety.*;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Pure typed JSONB transport. No Bean, DB, clock, key, environment or financial capability.
 * Strings returned here contain sensitive audit facts: callers must not log them. */
public final class PaymentSafetyPayloadCodecV1 {
    public record Limits(long maxPayloadBytes,int maxDepth,long maxTokens,int maxStringLength,int maxArrayElements) {
        public Limits {
            if(maxPayloadBytes<1 || maxPayloadBytes>96L*1024*1024 || maxDepth<1 || maxDepth>128 || maxTokens<1
                || maxStringLength<1 || maxArrayElements<1)throw PayloadEncodingV1.invalid();
        }
        public static Limits defaults(){return new Limits(96L*1024*1024,64,8_000_000,24*1024*1024,100_000);}
        static Limits event(){return new Limits(256*1024,32,100_000,256*1024,10_000);}
    }
    /** No toString payload disclosure. Total bound is enforced by encode/decodeAssessment, not this carrier. */
    public record Payloads(String input,String classification,String evidence,String rawFingerprint,String finalFingerprint,String recordDigest) {
        @Override public String toString(){return "PaymentSafetyPayloads[redacted]";}
    }
    private final PayloadEncodingV1 engine;
    public PaymentSafetyPayloadCodecV1(){this(Limits.defaults());}
    public PaymentSafetyPayloadCodecV1(Limits limits){engine=new PayloadEncodingV1(Objects.requireNonNull(limits));}
    public String encodeInput(PaymentSafetyReadFacts facts) {
        return engine.safe(()->{
            rawLimit(facts);
            var encoded=engine.encode(facts,PaymentSafetyReadFacts.class);
            var root=engine.object();root.put("format","payment-safety-input");root.put("version",1);
            root.set("facts",encoded);return engine.json(root);
        });
    }
    public PaymentSafetyReadFacts decodeInput(String json) {
        return engine.safe(()->{
            var root=engine.parse(json);engine.keys(root,"format","version","facts");
            if(!root.get("format").isString() || !"payment-safety-input".equals(root.get("format").stringValue())
                || !root.get("version").isIntegralNumber() || !root.get("version").canConvertToInt() || root.get("version").intValue()!=1)throw PayloadEncodingV1.invalid();
            var f=(PaymentSafetyReadFacts)engine.decode(root.get("facts"),PaymentSafetyReadFacts.class);rawLimit(f);return f;
        });
    }
    public Payloads encodeAssessment(AssessmentRecordDigestV1.Record record) {
        return engine.safe(()->{
            String input=encodeInput(record.facts());
            String classification=details(record.classification(),PaymentSafetyClassification.class);
            String evidence=details(record.evidence(),CaptureEvidenceValidation.class);
            engine.total(input,classification,evidence);
            return new Payloads(input,classification,evidence,PaymentSafetyInputFingerprint.sha256(record.facts()),
                PaymentSafetyInputFingerprint.sha256Final(record.facts(),record.evidence()),AssessmentRecordDigestV1.sha256(record));
        });
    }
    public AssessmentRecordDigestV1.Record decodeAssessment(AssessmentRecordDigestV1.Header h,Payloads p) {
        return engine.safe(()->{
            engine.total(p.input(),p.classification(),p.evidence());
            var f=decodeInput(p.input());
            var c=(PaymentSafetyClassification)readDetails(p.classification(),PaymentSafetyClassification.class);
            var e=(CaptureEvidenceValidation)readDetails(p.evidence(),CaptureEvidenceValidation.class);
            PayloadEncodingV1.verifyDigest(PaymentSafetyInputFingerprint.sha256(f),p.rawFingerprint());
            PayloadEncodingV1.verifyDigest(PaymentSafetyInputFingerprint.sha256Final(f,e),p.finalFingerprint());
            var r=new AssessmentRecordDigestV1.Record(h.runId(),h.orderId(),h.attemptNo(),h.phase(),h.preAssessmentId(),
                h.classificationContractVersion(),h.classifierImplVersion(),h.fingerprintFormatVersion(),h.contractManifestDigest(),f,e,c,h.observed());
            AssessmentRecordDigestV1.verify(r,p.recordDigest());return r;
        });
    }
    private String details(Object value,Class<?> type) {
        var root=(ObjectNode)engine.encode(value,type);root.put("version",1);return engine.json(root);
    }
    private Object readDetails(String json,Class<?> type) {
        var root=engine.parse(json);
        if(!root.isObject() || !root.has("version") || !root.get("version").isIntegralNumber() || !root.get("version").canConvertToInt() || root.get("version").intValue()!=1)throw PayloadEncodingV1.invalid();
        ((ObjectNode)root).remove("version");return engine.decode(root,type);
    }
    private void rawLimit(PaymentSafetyReadFacts facts) {
        // Uses the existing encoder and reader budget; never silently expands its 16 MiB contract.
        engine.rawLimit(facts);
    }
}
