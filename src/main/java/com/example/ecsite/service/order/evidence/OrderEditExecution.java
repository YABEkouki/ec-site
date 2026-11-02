package com.example.ecsite.service.order.evidence;

import java.time.Instant;
import java.util.*;

/** Immutable control facts and commands. No value here is an order-application or financial permit. */
public final class OrderEditExecution {
    private OrderEditExecution() {}
    public enum State { NOT_READY,QUEUED,OBSERVING,APPLYING,COMPLETED,FAILED_UNAPPLIED,CLOSED_UNAPPLIED,REVIEW_REQUIRED,RECOVERY_PENDING }
    public enum Disposition { REQUEUE,UNKNOWN,REVIEW }
    public enum Outcome { RECORDED,OBSERVED,NOT_OBSERVED,BLOCKED,CONFLICT,RUNTIME_UNAVAILABLE,COMMIT_UNKNOWN }
    public record Claim(UUID admissionId,UUID attemptId,long expectedVersion) {
        public Claim {identity(admissionId,attemptId,expectedVersion);}
    }
    public record Resolution(UUID admissionId,UUID resultId,UUID attemptId,long token,long expectedVersion,Disposition disposition,String reason) {
        public Resolution {identity(admissionId,resultId,expectedVersion);Objects.requireNonNull(attemptId);Objects.requireNonNull(disposition);
            if(token<=0||reason==null||reason.isBlank()||reason.length()>80)throw new IllegalArgumentException("Invalid resolution");}
    }
    public record Expired(UUID admissionId,UUID resultId,long expectedVersion) {
        public Expired {identity(admissionId,resultId,expectedVersion);}
    }
    public record Attempt(UUID id,UUID generation,long token,long version,Instant claimedAt,Instant leaseUntil) {}
    public record AttemptResult(UUID id,UUID attemptId,long token,long version,boolean recovery,String disposition,String reason) {}
    public record View(UUID admissionId,UUID generation,String admissionState,State state,Long token,Long version,
            UUID activeAttemptId,Instant leaseUntil,Instant nextAttemptAt,Instant automaticUntil,Integer attemptCount,
            List<Attempt> attempts,List<AttemptResult> results) {
        public View {attempts=List.copyOf(attempts);results=List.copyOf(results);}
    }
    public record Result(Outcome outcome,View view,String reason) {}
    private static void identity(UUID admission,UUID key,long version){
        if(admission==null||key==null||version<0)throw new IllegalArgumentException("Incomplete execution identity");
    }
}
