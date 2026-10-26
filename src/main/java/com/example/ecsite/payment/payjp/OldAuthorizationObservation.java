package com.example.ecsite.payment.payjp;

import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import java.util.UUID;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.springframework.web.client.RestClient;

/** Internal cross-package transport value, never an HTTP API or a send/closure permit.
 * No JSON, client secret or serializable representation is retained. */
public final class OldAuthorizationObservation {
    public static final Duration MAX_AGE=Duration.ofSeconds(5);
    /** Application callers obtain every field from locked, persisted request/Payment/CREATE data. */
    public record Expected(long orderId,UUID operationId,long oldPaymentId,String oldFlowId,long amount,
            CaptureConnectionIdentity connection,long version,String snapshot){
        public Expected{
            if(orderId<=0||oldPaymentId<=0||amount<=0||version<0||oldFlowId==null||oldFlowId.isBlank()
                ||snapshot==null||snapshot.isBlank())throw new IllegalArgumentException("Invalid old authorization context");
            Objects.requireNonNull(operationId);Objects.requireNonNull(connection);
        }
        public boolean matches(Expected other){return equals(other);}
        @Override public String toString(){return "OldAuthorizationExpected[redacted]";}
    }
    private final Expected expected;
    private final Instant startedAt,completedAt;
    private final long startedNanos,completedNanos;
    private final Clock clock;
    private final LongSupplier ticker;
    private final AtomicBoolean consumed=new AtomicBoolean();
    private OldAuthorizationObservation(Expected expected,Instant started,Instant completed,long nanos,long completedNanos,Clock clock,LongSupplier ticker){
        this.completedNanos=completedNanos;this.expected=expected;startedAt=started;completedAt=completed;startedNanos=nanos;this.clock=clock;this.ticker=ticker;
    }
    /** Only package-internal transport performs GET and mints the value; there is no DTO/JSON minting API. */
    static OldAuthorizationObservation retrieve(Expected expected,RestClient client,Clock clock,LongSupplier ticker){
        Objects.requireNonNull(expected);var started=clock.instant();long nanos=ticker.getAsLong();
        var body=client.get().uri("/v2/payment_flows/{id}",expected.oldFlowId()).retrieve().body(tools.jackson.databind.JsonNode.class);
        var completed=clock.instant();long completedNanos=ticker.getAsLong();
        if(body==null||!body.isObject()||!expected.oldFlowId().equals(text(body,"id"))
            ||!"requires_capture".equals(text(body,"status"))||!"jpy".equals(text(body,"currency"))
            ||!"manual".equals(text(body,"capture_method")))throw invalid();
        var amount=body.get("amount");var capturable=body.get("amount_capturable");
        var received=body.get("amount_received");var mode=body.get("livemode");
        if(!integerEquals(amount,expected.amount())||!integerEquals(capturable,expected.amount())
            ||received==null||(!received.isNull()&&!integerEquals(received,0))
            ||mode==null||!mode.isBoolean()||mode.booleanValue()!=expected.connection().mode().equals("LIVE"))throw invalid();
        var result=new OldAuthorizationObservation(expected,started,completed,nanos,completedNanos,clock,ticker);
        result.requireFresh();return result;
    }
    private static String text(tools.jackson.databind.JsonNode body,String field){
        var value=body.get(field);if(value==null||!value.isString())throw invalid();return value.stringValue();
    }
    private static boolean integerEquals(tools.jackson.databind.JsonNode value,long expected){
        return value!=null&&value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()==expected;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Old authorization rejected");}
    public void requireTarget(long orderId,UUID operationId){
        if(expected.orderId()!=orderId||!expected.operationId().equals(operationId))
            throw new IllegalArgumentException("Old authorization target mismatch");
    }
    public void requireMatches(Expected current){if(!expected.matches(current))throw new IllegalStateException("Old authorization observation context changed");}
    private void requireTimeWindow(){
        long elapsed=ticker.getAsLong()-startedNanos;var now=clock.instant();
        long getElapsed=completedNanos-startedNanos;
        if(getElapsed<0||getElapsed>MAX_AGE.toNanos()||elapsed<getElapsed||completedAt.isBefore(startedAt)||now.isBefore(completedAt)||elapsed<0||elapsed>MAX_AGE.toNanos()
            ||Duration.between(startedAt,now).compareTo(MAX_AGE)>0)throw new IllegalStateException("Old authorization observation expired or clock invalid");
    }
    public void requireFresh(){
        requireTimeWindow();if(consumed.get())throw new IllegalStateException("Old authorization observation already consumed");
    }
    /** Irreversibly burns the observation, including mismatches and failed/rolled-back claims. */
    public void consumeFor(Expected current){
        if(!consumed.compareAndSet(false,true))throw new IllegalStateException("Old authorization observation already consumed");
        requireMatches(current);requireTimeWindow();
    }
    /** Used as the first action of the dedicated completion transaction; no caller-provided identity. */
    public Expected consumeBound(){consumeFor(expected);return expected;}
    /** Consumed observations may only attest their original context until expiry. */
    public void requireConsumedFor(Expected current){
        if(!consumed.get())throw new IllegalStateException("Old authorization observation has not been consumed");
        requireMatches(current);requireTimeWindow();
    }
    public Instant startedAt(){return startedAt;}
    public Instant completedAt(){return completedAt;}
    @Override public String toString(){return "OldAuthorizationObservation[redacted]";}
}
