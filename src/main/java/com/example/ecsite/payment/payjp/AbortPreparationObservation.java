package com.example.ecsite.payment.payjp;

import com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.springframework.web.client.RestClient;

/** Internal cross-package transport value, never an HTTP API or a send/closure permit.
 * No JSON, client secret or serializable representation is retained. */
public final class AbortPreparationObservation {
    public static final Duration MAX_AGE=Duration.ofSeconds(5);
    private final Expected expected;
    private final Instant startedAt,completedAt;
    private final long startedNanos,completedNanos;
    private final Clock clock;
    private final LongSupplier ticker;
    private final AtomicBoolean consumed=new AtomicBoolean();
    private AbortPreparationObservation(Expected expected,Instant started,Instant completed,long nanos,long completedNanos,Clock clock,LongSupplier ticker){
        this.expected=expected;this.completedNanos=completedNanos;startedAt=started;completedAt=completed;startedNanos=nanos;this.clock=clock;this.ticker=ticker;
    }
    /** Only package-internal transport performs GET and mints the value; there is no DTO/JSON minting API. */
    static AbortPreparationObservation retrieve(Expected expected,RestClient client,Clock clock,LongSupplier ticker){
        Objects.requireNonNull(expected);var started=clock.instant();long nanos=ticker.getAsLong();
        var body=client.get().uri("/v2/payment_flows/{id}",expected.flow().newFlowId()).retrieve().body(tools.jackson.databind.JsonNode.class);
        var completed=clock.instant();long completedNanos=ticker.getAsLong();
        PayJpPaymentFlowObservation.requireRequestedAbortAuthorization(expected.flow(),body);
        var result=new AbortPreparationObservation(expected,started,completed,nanos,completedNanos,clock,ticker);
        result.requireFresh();return result;
    }
    public void requireMatches(Expected current){if(!expected.matches(current))throw new IllegalStateException("Abort observation context changed");}
    private void requireTimeWindow(){
        long elapsed=ticker.getAsLong()-startedNanos;var now=clock.instant();
        long getElapsed=completedNanos-startedNanos;
        if(getElapsed<0||getElapsed>MAX_AGE.toNanos()||elapsed<getElapsed||completedAt.isBefore(startedAt)||now.isBefore(completedAt)||elapsed<0||elapsed>MAX_AGE.toNanos()
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
    /** Used as the first action of the dedicated preparation transaction; no caller-provided identity. */
    public Expected consumeBound(){consumeFor(expected);return expected;}
    /** Consumed observations may only attest their original preparation context until expiry. */
    public void requireConsumedFor(Expected current){
        if(!consumed.get())throw new IllegalStateException("Abort observation has not been consumed");
        requireMatches(current);requireTimeWindow();
    }
    public void requireTarget(long orderId,java.util.UUID operationId){
        if(expected.flow().orderId()!=orderId||!expected.flow().operationId().equals(operationId))
            throw new IllegalArgumentException("Abort preparation observation target mismatch");
    }
    public Instant startedAt(){return startedAt;}
    public Instant completedAt(){return completedAt;}
    @Override public String toString(){return "AbortPreparationObservation[redacted]";}
}
