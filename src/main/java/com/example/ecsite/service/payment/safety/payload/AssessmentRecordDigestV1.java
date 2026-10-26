package com.example.ecsite.service.payment.safety.payload;

import com.example.ecsite.service.payment.PaymentSafetyClassification;
import com.example.ecsite.service.payment.safety.*;
import java.util.*;

/** Audit identity only; no financial execution permission. No DB-generated identity/time is accepted. */
public final class AssessmentRecordDigestV1 {
    private AssessmentRecordDigestV1() {}
    public record Header(UUID runId, long orderId, int attemptNo, String phase, Long preAssessmentId,
            String classificationContractVersion, String classifierImplVersion, short fingerprintFormatVersion,
            String contractManifestDigest, ObservedValues observed) {
        public Header {
            Objects.requireNonNull(runId);Objects.requireNonNull(observed);
            if(orderId<=0 || attemptNo<=0 || !Set.of("OBSERVED","PRE_APPLY","POST_APPLY").contains(phase)
                || ("POST_APPLY".equals(phase)!=(preAssessmentId!=null)) || (preAssessmentId!=null && preAssessmentId<=0)
                || classificationContractVersion==null || classificationContractVersion.isBlank()
                || classifierImplVersion==null || classifierImplVersion.isBlank()
                || fingerprintFormatVersion!=PaymentSafetyInputFingerprint.FORMAT_VERSION
                || contractManifestDigest==null || !contractManifestDigest.matches("[0-9a-f]{64}"))throw PayloadEncodingV1.invalid();
        }
    }
    public record ObservedValues(String orderStatus, int orderTotalAmount, int orderContentRevision,
            Long activePaymentId, int captureTransactionCount, int captureRequestCount,
            String captureEvidenceSummary, String financialResumption) {
        public ObservedValues {
            if(captureTransactionCount<0 || captureRequestCount<0 || !"NOT_EVALUATED".equals(financialResumption)
                || captureEvidenceSummary==null || !Set.of("VERIFIED","UNVERIFIED","MISSING","MISMATCH","NOT_APPLICABLE").contains(captureEvidenceSummary))throw PayloadEncodingV1.invalid();
        }
    }
    public record Record(UUID runId, long orderId, int attemptNo, String phase, Long preAssessmentId,
            String classificationContractVersion, String classifierImplVersion, short fingerprintFormatVersion,
            String contractManifestDigest, PaymentSafetyReadFacts facts, CaptureEvidenceValidation evidence,
            PaymentSafetyClassification classification, ObservedValues observed) {
        public Record {
            Objects.requireNonNull(facts);Objects.requireNonNull(evidence);Objects.requireNonNull(classification);
            new Header(runId,orderId,attemptNo,phase,preAssessmentId,classificationContractVersion,classifierImplVersion,
                fingerprintFormatVersion,contractManifestDigest,observed);
            if(!Objects.equals(facts.order().id(),orderId))throw PayloadEncodingV1.invalid();
        }
        @Override public String toString(){return "AssessmentRecord[redacted]";}
        public Header header(){return new Header(runId,orderId,attemptNo,phase,preAssessmentId,classificationContractVersion,
            classifierImplVersion,fingerprintFormatVersion,contractManifestDigest,observed);}
    }
    public static String sha256(Record record) {
        return PayloadEncodingV1.digest("ec-site/payment-safety/assessment-record", Objects.requireNonNull(record));
    }
    public static void verify(Record record,String expected) {
        PayloadEncodingV1.verifyDigest(sha256(record),expected);
    }
}
