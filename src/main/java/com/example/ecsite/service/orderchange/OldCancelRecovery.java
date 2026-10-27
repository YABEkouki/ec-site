package com.example.ecsite.service.orderchange;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.example.ecsite.payment.orderchange.StoredOrderChangeRequest;
import com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation;

/** Internal recovery values. None is a financial-send permit. */
public final class OldCancelRecovery {
    private OldCancelRecovery(){}
    public enum Result { COMPLETED, COMMIT_PENDING, HOLD, CONFLICT }
    public enum Detail { COMPLETED, COMMIT_PENDING, OLD_EVIDENCE_UNCONFIRMED, NEW_AUTH_UNAVAILABLE, GET_UNKNOWN, VERSION_CONFLICT, NOT_ELIGIBLE, AUDIT_FAILED, HOLD }
    public record Outcome(Result result,Detail detail){}
    static final class EvidenceFailure extends IllegalStateException {
        final Detail detail;
        EvidenceFailure(Detail detail){super("Recovery evidence unconfirmed");this.detail=detail;}
    }
    public record Command(long orderId,UUID operationId,UUID requestId,long expectedVersion,String reason){
        public Command{
            if(orderId<=0||operationId==null||requestId==null||expectedVersion<0||!"VERIFY_OLD_CANCEL".equals(reason))
                throw new IllegalArgumentException("Invalid recovery command");
        }
    }
    public record Actor(long id,String username){}
    public record Snapshot(Command command,long version,boolean committing,long oldPaymentId,long newPaymentId,
            StoredOrderChangeRequest cancellation,StoredOrderChangeRequest creation,String oldFlow,String newFlow,String digest){
        @Override public String toString(){return "OldCancelRecoverySnapshot[redacted]";}
    }
    /** Process-local, single-use evidence. Wall and monotonic clocks are both checked, including lock wait. */
    static final class Observation {
        final Snapshot snapshot;
        final PayJpPaymentFlowObservation oldFlow,newFlow;
        final Instant started,finished;
        final long startNanos,endNanos;
        private final AtomicBoolean consumed=new AtomicBoolean();
        Observation(Snapshot snapshot,PayJpPaymentFlowObservation oldFlow,PayJpPaymentFlowObservation newFlow,
                Instant started,Instant finished,long startNanos,long endNanos){
            this.snapshot=snapshot;this.oldFlow=oldFlow;this.newFlow=newFlow;this.started=started;this.finished=finished;
            this.startNanos=startNanos;this.endNanos=endNanos;
        }
        void consume(Clock clock,java.util.function.LongSupplier nanos){
            var now=clock.instant();long elapsed=nanos.getAsLong()-startNanos;
            if(started==null||finished==null||finished.isBefore(started)||now.isBefore(finished)
                    ||Duration.between(started,now).compareTo(Duration.ofSeconds(5))>0
                    ||endNanos-startNanos<0||elapsed<endNanos-startNanos||elapsed>5_000_000_000L
                    ||!consumed.compareAndSet(false,true))throw new IllegalStateException("Recovery observation stale or consumed");
        }
    }
}
