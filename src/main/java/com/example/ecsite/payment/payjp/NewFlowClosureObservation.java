package com.example.ecsite.payment.payjp;

import java.time.*;
import java.util.Objects;
import com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence;
import org.springframework.web.client.RestClient;

/** Internal cross-package transport value, never an HTTP API, send permit or permission to release stock.
 * No JSON, client secret or serializable representation is retained. */
public final class NewFlowClosureObservation {
    public static final class Expired extends IllegalStateException {
        private Expired(){super("Closure observation expired or clock invalid");}
    }
    public static final Duration MAX_AGE=Duration.ofSeconds(5);
    public record RequestBinding(long id,String fingerprint) {
        public RequestBinding { if(id<=0||fingerprint==null||!fingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid closure request binding"); }
        @Override public String toString(){return "ClosureRequest[redacted]";}
    }
    public record AbortIntent(java.util.UUID requestId,LocalDateTime requestedAt,String requesterType,String reason,long expectedVersion) {
        public AbortIntent { Objects.requireNonNull(requestId);Objects.requireNonNull(requestedAt);
            if(expectedVersion<0||!java.util.List.of("CUSTOMER","ADMINISTRATOR","SYSTEM").contains(requesterType)
                ||!java.util.List.of("CUSTOMER_REQUEST","BROWSER_INTERRUPTED","AUTHENTICATION_INTERRUPTED","COMMUNICATION_FAILURE","PAYMENT_FAILURE").contains(reason))
                throw new IllegalArgumentException("Invalid closure abort intent"); }
        @Override public String toString(){return "ClosureIntent[redacted]";}
    }
    /** Canonical, explicitly selected financial columns only; no provider payload or secrets. */
    public record FinancialSnapshot(String payment,java.util.List<String> transactions,String dispatchState) {
        public FinancialSnapshot { Objects.requireNonNull(payment);transactions=java.util.List.copyOf(transactions);Objects.requireNonNull(dispatchState); }
        @Override public String toString(){return "ClosureFinancialSnapshot[redacted]";}
    }
    private final Expected expected;
    private final NewFlowTerminationEvidence evidence;
    private final Instant startedAt,completedAt;
    private final long startedNanos,completedNanos;
    private final Clock clock;
    private final LongSupplier ticker;
    private final AtomicBoolean consumed=new AtomicBoolean();
    private NewFlowClosureObservation(Expected expected,Instant started,Instant completed,long nanos,long completedNanos,Clock clock,LongSupplier ticker,NewFlowTerminationEvidence evidence){
        this.expected=expected;this.evidence=evidence;this.completedNanos=completedNanos;startedAt=started;completedAt=completed;startedNanos=nanos;this.clock=clock;this.ticker=ticker;
    }
    /** Safe classification carries no provider payload; only CLOSED can carry transport-minted evidence. */
    public static final class Assessment {
        public enum Kind { CLOSED, NOT_CLOSED, RETRY_LATER, MANUAL_REVIEW_REQUIRED }
        private final Kind kind;
        private final NewFlowClosureObservation closure;
        private Assessment(Kind kind,NewFlowClosureObservation closure){this.kind=kind;this.closure=closure;}
        public Kind kind(){return kind;}
        public java.util.Optional<NewFlowClosureObservation> closure(){return java.util.Optional.ofNullable(closure);}
        @Override public String toString(){return "ClosureAssessment["+kind+"]";}
    }
    /** Only package-internal transport performs GET and mints the value; no DTO/JSON minting API. */
    static NewFlowClosureObservation retrieve(Expected expected,RestClient client,Clock clock,LongSupplier ticker){
        return assess(expected,client,clock,ticker).closure().orElseThrow(()->new IllegalArgumentException("Flow not closed"));
    }
    static Assessment assess(Expected expected,RestClient client,Clock clock,LongSupplier ticker){
        Objects.requireNonNull(expected);var started=clock.instant();long nanos=ticker.getAsLong();
        var body=client.get().uri("/v2/payment_flows/{id}",expected.flow().newFlowId()).retrieve().body(tools.jackson.databind.JsonNode.class);
        var completed=clock.instant();long completedNanos=ticker.getAsLong();
        // Nonterminal classification also requires the saved identity, never response-derived expectations.
        var f=expected.flow();
        if(body==null||!body.isObject()||!f.newFlowId().equals(text(body,"id"))
            ||!"jpy".equals(text(body,"currency"))||!"manual".equals(text(body,"capture_method")))throw invalid();
        var amount=body.get("amount");var mode=body.get("livemode");var metadata=body.get("metadata");
        if(amount==null||!amount.isIntegralNumber()||!amount.canConvertToLong()||amount.longValue()!=f.amount()
            ||mode==null||!mode.isBoolean()||mode.booleanValue()!=f.connection().mode().equals("LIVE")
            ||metadata==null||!metadata.isObject()||!Long.toString(f.orderId()).equals(text(metadata,"order_id"))
            ||!f.operationId().toString().equals(text(metadata,"order_change_operation_id")))throw invalid();
        String status=text(body,"status");
        // Validate monetary JSON types without treating nullable observations as terminal evidence.
        PayJpPaymentFlowObservation.fromJson(f.newFlowId(),status,(long)f.amount(),"jpy",body.get("amount_received"),
            body.get("amount_capturable"),"manual",mode.booleanValue(),null,null,java.util.Map.of());
        if(!"canceled".equals(status)){
            // No token and no writer for these classifications, even if the GET is HTTP 200.
            long elapsed=ticker.getAsLong()-nanos;
            if(completedNanos-nanos<0||elapsed<completedNanos-nanos||elapsed>MAX_AGE.toNanos()
                ||completed.isBefore(started)||clock.instant().isBefore(completed)
                ||Duration.between(started,clock.instant()).compareTo(MAX_AGE)>0)throw new Expired();
            var kind=switch(status){case "requires_capture"->Assessment.Kind.NOT_CLOSED;
                case "succeeded"->Assessment.Kind.MANUAL_REVIEW_REQUIRED;default->Assessment.Kind.RETRY_LATER;};
            return new Assessment(kind,null);
        }
        var evidence=NewFlowTerminationEvidence.verify(f,f.connection(),body);
        var result=new NewFlowClosureObservation(expected,started,completed,nanos,completedNanos,clock,ticker,evidence);
        result.requireFresh();return new Assessment(Assessment.Kind.CLOSED,result);
    }
    private static String text(tools.jackson.databind.JsonNode body,String field){
        var value=body.get(field);if(value==null||!value.isString())throw invalid();return value.stringValue();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Closure assessment rejected");}
    public void requireMatches(Expected current){if(!expected.matches(current))throw new IllegalStateException("Closure observation context changed");}
    private void requireTimeWindow(){
        long elapsed=ticker.getAsLong()-startedNanos;var now=clock.instant();
        long getElapsed=completedNanos-startedNanos;
        if(getElapsed<0||getElapsed>MAX_AGE.toNanos()||elapsed<getElapsed||completedAt.isBefore(startedAt)||now.isBefore(completedAt)||elapsed<0||elapsed>MAX_AGE.toNanos()
            ||Duration.between(startedAt,now).compareTo(MAX_AGE)>0)throw new Expired();
    }
    public void requireFresh(){
        requireTimeWindow();if(consumed.get())throw new IllegalStateException("Closure observation already consumed");
    }
    /** Irreversibly burns the observation, including mismatches and failed/rolled-back claims. */
    public void consumeFor(Expected current){
        if(!consumed.compareAndSet(false,true))throw new IllegalStateException("Closure observation already consumed");
        requireMatches(current);requireTimeWindow();
    }
    /** Used as the first action of a future closure-writer transaction; no caller-provided identity. */
    public Expected consumeBound(){consumeFor(expected);return expected;}
    public void requireTarget(long orderId,java.util.UUID operationId){
        if(expected.flow().orderId()!=orderId||!expected.flow().operationId().equals(operationId))
            throw new IllegalArgumentException("Closure observation target mismatch");
    }
    /** Writer-only attestation after irreversible consumption; includes binding and elapsed time. */
    public void requireConsumedFor(Expected current){
        if(!consumed.get())throw new IllegalStateException("Closure observation not consumed");
        requireMatches(current);requireTimeWindow();
    }
    public Instant startedAt(){return startedAt;}
    public Instant completedAt(){return completedAt;}
    @Override public String toString(){return "NewFlowClosureObservation[redacted]";}
}
