package com.example.ecsite.payment.orderchange;

import java.time.Instant;
import java.util.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;

/** V78 immutable values only. Neither a dispatch capability nor authorization to
 * settle a transaction, close an admission, resolve an execution attempt or release stock.
 * Expected ownership/local facts must come from saved rows, never provider metadata. */
public final class ThreeDsFailureEvidence {
    private ThreeDsFailureEvidence() {}
    public enum State { REQUESTED, FAILURE_OBSERVED, TERMINAL_OBSERVED }
    public enum Kind { THREE_DS_FAILURE, FLOW_TERMINAL }
    public record Ownership(UUID controlId,UUID admissionId,UUID operationId,long orderId,UUID generationId,
            long oldPaymentId,long newPaymentId,long authorizationId,long createRequestId,String flowId,
            int amount,int baseRevision,CaptureConnectionIdentity connection) {
        public Ownership {
            require(controlId!=null&&admissionId!=null&&operationId!=null&&generationId!=null&&connection!=null
                &&orderId>0&&oldPaymentId>0&&newPaymentId>0&&oldPaymentId!=newPaymentId&&authorizationId>0
                &&createRequestId>0&&flowId!=null&&!flowId.isBlank()&&flowId.length()<=255
                &&amount>=50&&amount<=9999999&&baseRevision>=0);
        }
        @Override public String toString(){return "ThreeDsFailureOwnership[redacted]";}
    }
    /** intentHistoryDigest is V78 control.history_digest; historyDigest is the current
     * f125_failure_history value used for this observation (it may change during cancellation). */
    public record Context(Ownership ownership,long version,long executionVersion,State state,byte[] intentHistoryDigest,byte[] historyDigest,
                          int evidenceVersion,Long cancelRequestId) {
        public Context {
            require(ownership!=null&&state!=null&&version==state.ordinal()&&executionVersion>=0
                &&intentHistoryDigest!=null&&intentHistoryDigest.length==32&&historyDigest!=null&&historyDigest.length==32&&evidenceVersion==1
                &&(cancelRequestId==null||cancelRequestId>0)
                &&(state!=State.REQUESTED||cancelRequestId==null));
            intentHistoryDigest=intentHistoryDigest.clone();historyDigest=historyDigest.clone();
            require(state!=State.REQUESTED||java.security.MessageDigest.isEqual(intentHistoryDigest,historyDigest));
        }
        @Override public byte[] intentHistoryDigest(){return intentHistoryDigest.clone();}
        @Override public byte[] historyDigest(){return historyDigest.clone();}
        @Override public String toString(){return "ThreeDsFailureContext[redacted]";}
    }
    public record FinancialResult(UUID operationId,long paymentId,String type,String status) {}
    public record CancelBinding(long requestId,UUID operationId,long orderId,long paymentId,String flowId,
                                int amount,String kind,CaptureConnectionIdentity connection) {}
    /** Joined saved-row facts, not a claim derived from an external response.
     * A future locked repository must perform V78's full baseline/runtime checks too. */
    public record LocalFacts(Ownership ownership,long executionVersion,String oldPaymentStatus,String newPaymentStatus,
            String authorizationStatus,boolean authorizationCompleted,boolean activeAttempt,boolean activeLease,
            boolean switchDecided,boolean orderApplied,boolean oldCancelRequested,List<FinancialResult> results,
            byte[] historyDigest,CancelBinding cancel) {
        public LocalFacts {require(ownership!=null&&results!=null&&historyDigest!=null&&historyDigest.length==32);results=List.copyOf(results);historyDigest=historyDigest.clone();}
        @Override public byte[] historyDigest(){return historyDigest.clone();}
        @Override public String toString(){return "ThreeDsFailureLocalFacts[redacted]";}
    }
    public record Window(Instant startedAt,Instant finishedAt) {
        public Window {require(startedAt!=null&&finishedAt!=null&&!finishedAt.isBefore(startedAt)
            &&startedAt.getNano()%1000==0&&finishedAt.getNano()%1000==0);}
    }
    public record StoredObservation(UUID id,UUID controlId,long controlVersion,Kind kind,String document,Window window,
            byte[] historyDigest,int evidenceVersion,String recordingXid,Instant recordedAt,byte[] canonical,byte[] digest) {
        public StoredObservation {
            require(id!=null&&controlId!=null&&controlVersion>=0&&kind!=null&&document!=null&&window!=null
                &&historyDigest!=null&&historyDigest.length==32&&recordingXid!=null&&recordedAt!=null);
            historyDigest=historyDigest.clone();canonical=copy(canonical);digest=copy(digest);
        }
        @Override public byte[] historyDigest(){return historyDigest.clone();}
        @Override public byte[] canonical(){return copy(canonical);}
        @Override public byte[] digest(){return copy(digest);}
        @Override public String toString(){return "StoredThreeDsFailureObservation[redacted]";}
    }
    /** A validated, allowlisted observation. id/digest are absent until stored-row verification. */
    public static final class Observation {
        private final Context context;private final Kind kind;private final String document;
        private final Window window;private final UUID id;private final Instant recordedAt;private final byte[] digest;
        Observation(Context context,Kind kind,String document,Window window,UUID id,Instant recordedAt,byte[] digest) {
            this.context=context;this.kind=kind;this.document=document;this.window=window;this.id=id;this.recordedAt=recordedAt;this.digest=copy(digest);
        }
        public Context context(){return context;} public Kind kind(){return kind;} public String document(){return document;}
        public Window window(){return window;} public UUID id(){return id;} public Instant recordedAt(){return recordedAt;} public byte[] digest(){return copy(digest);}
        @Override public String toString(){return "ThreeDsFailureObservation[redacted]";}
    }
    /** Exact saved attempt/result JSON rows serialized under UTC/ISO. V78 canonicalizes their full contents;
     * do not project away fields or reconstruct historical rows from current control values. */
    public record UnknownSource(UUID attemptId,UUID resultId,String attemptAudit,String resultAudit,boolean conclusiveResultExists) {
        public UnknownSource {require(attemptId!=null&&resultId!=null&&attemptAudit!=null&&resultAudit!=null);}
        @Override public String toString(){return "ThreeDsFailureUnknownSource[redacted]";}
    }
    public record StoredResolution(UUID id,UUID controlId,UUID unknownResultId,UUID terminalObservationId,
            long controlVersion,String disposition,String document,int evidenceVersion,String recordingXid,
            Instant recordedAt,byte[] canonical,byte[] digest) {
        public StoredResolution {
            require(id!=null&&controlId!=null&&unknownResultId!=null&&terminalObservationId!=null&&document!=null
                &&recordingXid!=null&&recordedAt!=null);
            canonical=copy(canonical);digest=copy(digest);
        }
        @Override public byte[] canonical(){return copy(canonical);}
        @Override public byte[] digest(){return copy(digest);}
        @Override public String toString(){return "StoredThreeDsFailureResolution[redacted]";}
    }
    /** Still only the V78 proposed resolution, never an effective UNKNOWN settlement. */
    public static final class ResolutionEvidence {
        private final UUID id,controlId,unknownResultId,terminalObservationId;private final byte[] digest;
        ResolutionEvidence(StoredResolution r){id=r.id();controlId=r.controlId();unknownResultId=r.unknownResultId();terminalObservationId=r.terminalObservationId();digest=r.digest();}
        public UUID id(){return id;} public UUID controlId(){return controlId;} public UUID unknownResultId(){return unknownResultId;}
        public UUID terminalObservationId(){return terminalObservationId;} public byte[] digest(){return digest.clone();}
        @Override public String toString(){return "ThreeDsFailureResolutionEvidence[redacted]";}
    }
    static byte[] copy(byte[] value){return value==null?null:value.clone();}
    static void require(boolean condition){if(!condition)throw invalid();}
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid 3DS failure evidence");}
}
