package com.example.ecsite.payment.payjp;

import java.security.MessageDigest;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence;
import org.springframework.web.client.RestClient;

/** Internal cross-package transport value, never an HTTP API or a send/closure permit.
 * No JSON, client secret or serializable representation is retained. */
public final class RequestedAbortObservation {
    public static final Duration MAX_AGE=Duration.ofSeconds(5);
    /** Application callers obtain every field from locked, persisted request/Payment/CREATE data. */
    public record Expected(long requestId,long paymentId,long createRequestId,byte[] fingerprint,long version,
            NewFlowTerminationEvidence.Expected flow){
        public Expected{
            if(requestId<=0||paymentId<=0||createRequestId<=0||version<0||fingerprint==null||fingerprint.length!=32)
                throw new IllegalArgumentException("Invalid requested abort observation context");
            Objects.requireNonNull(flow);fingerprint=fingerprint.clone();
        }
        @Override public byte[] fingerprint(){return fingerprint.clone();}
        public boolean matches(Expected other){return other!=null&&requestId==other.requestId&&paymentId==other.paymentId
            &&createRequestId==other.createRequestId&&version==other.version&&flow.equals(other.flow)
            &&MessageDigest.isEqual(fingerprint,other.fingerprint);}
        @Override public String toString(){return "RequestedAbortExpected[redacted]";}
    }
    private final Expected expected;
    private final Instant startedAt,completedAt;
    private final long startedNanos;
    private final Clock clock;
    private final LongSupplier ticker;
    private final AtomicBoolean consumed=new AtomicBoolean();
    private RequestedAbortObservation(Expected expected,Instant started,Instant completed,long nanos,Clock clock,LongSupplier ticker){
        this.expected=expected;startedAt=started;completedAt=completed;startedNanos=nanos;this.clock=clock;this.ticker=ticker;
    }
    /** Only package-internal transport performs GET and mints the value; there is no DTO/JSON minting API. */
    static RequestedAbortObservation retrieve(Expected expected,RestClient client,Clock clock,LongSupplier ticker){
        Objects.requireNonNull(expected);var started=clock.instant();long nanos=ticker.getAsLong();
        var body=client.get().uri("/v2/payment_flows/{id}",expected.flow().newFlowId()).retrieve().body(tools.jackson.databind.JsonNode.class);
        var completed=clock.instant();
        PayJpPaymentFlowObservation.requireRequestedAbortAuthorization(expected.flow(),body);
        var result=new RequestedAbortObservation(expected,started,completed,nanos,clock,ticker);
        result.requireFresh();return result;
    }
    public void requireMatches(Expected current){if(!expected.matches(current))throw new IllegalStateException("Abort observation context changed");}
    private void requireTimeWindow(){
        long elapsed=ticker.getAsLong()-startedNanos;var now=clock.instant();
        if(completedAt.isBefore(startedAt)||now.isBefore(completedAt)||elapsed<0||elapsed>MAX_AGE.toNanos()
            ||Duration.between(startedAt,now).compareTo(MAX_AGE)>0)throw new IllegalStateException("Abort observation expired or clock invalid");
    }
    public void requireFresh(){
        requireTimeWindow();if(consumed.get())throw new IllegalStateException("Abort observation already consumed");
    }
    /** Irreversibly burns the observation, including mismatches and failed/rolled-back claims. */
    public void consumeFor(Expected current){
        if(!consumed.compareAndSet(false,true))throw new IllegalStateException("Abort observation already consumed");
        requireMatches(current);requireTimeWindow();
    }
    /** Used as the first action of the dedicated claim transaction; no caller-provided identity. */
    public Expected consumeBound(){consumeFor(expected);return expected;}
    /** Consumed observations may only attest their original committed Dispatch until expiry. */
    public void requireConsumedFor(Expected current){
        if(!consumed.get())throw new IllegalStateException("Abort observation has not been consumed");
        requireMatches(current);requireTimeWindow();
    }
    public Instant startedAt(){return startedAt;}
    public Instant completedAt(){return completedAt;}
    @Override public String toString(){return "RequestedAbortObservation[redacted]";}
}
