package com.example.ecsite.service.payment.safety.payload;

import java.util.*;

/** Detached ORDER-only input. Assessment rows must have been verified by the caller; this model
 * neither authenticates their existence nor authorizes finance. No lifecycle history is inferred.
 */
public record ObservationLedgerSnapshot(UUID runId,ObservationRunRequest.Manifest manifest,String manifestDigest,
        List<LedgerEvent> events,List<VerifiedAssessment> assessments,long currentCursor,Limits limits) {
    public ObservationLedgerSnapshot {
        Objects.requireNonNull(runId);Objects.requireNonNull(manifest);Objects.requireNonNull(limits);
        events=List.copyOf(events);assessments=List.copyOf(assessments);
    }
    public record LedgerEvent(UUID eventUuid,String recordDigest,AuditEventDigestV1.Record record) {
        @Override public String toString(){return "LedgerEvent[redacted]";}
    }
    public enum Disposition { SELECTED, NORMAL_NULL, REVIEW }
    /** An externally verified OBSERVED Assessment, not an Assessment to create or save. */
    public record VerifiedAssessment(UUID runId,long orderId,int attemptNo,long assessmentId,
            String recordDigest,Disposition disposition) {
        public VerifiedAssessment {
            ObservationOrderContextV1.hash(recordDigest);
            if(runId==null || orderId<=0 || attemptNo<1 || attemptNo>3 || assessmentId<=0 || disposition==null)throw PayloadEncodingV1.invalid();
        }
    }
    public record Limits(int maxEvents,long maxInputBytes) {
        public Limits {
            if(maxEvents<1 || maxEvents>6000 || maxInputBytes<1 || maxInputBytes>64L*1024*1024)throw PayloadEncodingV1.invalid();
        }
        public static Limits defaults(){return new Limits(6000,64L*1024*1024);}
    }
    @Override public String toString(){return "ObservationLedgerSnapshot[redacted]";}
}
