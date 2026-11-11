package com.example.ecsite.payment.payjp;

import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GET-only, opaque, process-local receipt. Cannot be minted from caller JSON or restored from DB.
 * A fresh receipt attests this observation only; it is never a Capture/send permit. */
public final class OrderAuthorizationObservation {
    public static final Duration MAX_AGE=Duration.ofSeconds(5);
    public record Expected(long orderId,long paymentId,long authorizationId,String flowId,int amount,int revision,
            String localFingerprint,CaptureConnectionIdentity connection,UUID authorizationOperationId) {
        public Expected {
            if(orderId<=0||paymentId<=0||authorizationId<=0||amount<=0||revision<0||flowId==null||!flowId.matches("pfw_[A-Za-z0-9_-]{1,251}")
                ||localFingerprint==null||!localFingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid observation target");
            Objects.requireNonNull(connection);
        }
        @Override public String toString(){return "OrderAuthorizationExpected[redacted]";}
    }
    private final Expected expected;
    private final Instant started,finished;
    private final long startNanos,finishNanos;
    private final Clock clock;
    private final LongSupplier ticker;
    private final String payload;
    private final Instant expiresAt;
    private final AtomicBoolean used=new AtomicBoolean();
    private OrderAuthorizationObservation(Expected e,Instant start,Instant finish,long a,long b,Clock c,LongSupplier t,String payload,Instant expiresAt) {
        expected=e;started=start;finished=finish;startNanos=a;finishNanos=b;clock=c;ticker=t;this.payload=payload;this.expiresAt=expiresAt;
    }
    static OrderAuthorizationObservation retrieve(Expected e,RestClient client,Clock clock,LongSupplier ticker) {
        Instant start=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);long a=ticker.getAsLong();
        var response=client.get().uri("/v2/payment_flows/{id}",e.flowId()).retrieve().toEntity(JsonNode.class);
        var body=response.getBody();Instant finish=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);long b=ticker.getAsLong();
        if(response.getStatusCode().value()!=200||body==null||!body.isObject()
            ||!e.flowId().equals(text(body,"id"))||!"requires_capture".equals(text(body,"status"))
            ||!"manual".equals(text(body,"capture_method"))||!"jpy".equals(text(body,"currency"))
            ||!number(body,"amount",e.amount())||!number(body,"amount_capturable",e.amount()))throw invalid();
        var received=body.get("amount_received");var mode=body.get("livemode");
        if(received==null||(!received.isNull()&&!number(body,"amount_received",0))||mode==null||!mode.isBoolean()
            ||mode.booleanValue()!=e.connection().mode().equals("LIVE"))throw invalid();
        if(e.authorizationOperationId()!=null) {
            var metadata=body.get("metadata");
            if(metadata==null||!metadata.isObject()||!Long.toString(e.orderId()).equals(text(metadata,"order_id"))
                ||!e.authorizationOperationId().toString().equals(text(metadata,"order_change_operation_id")))throw invalid();
        }
        var canceled=body.get("canceled_at");if(canceled!=null&&!canceled.isNull())throw invalid();
        var captured=body.get("captured_at");if(captured!=null&&!captured.isNull())throw invalid();
        var expiry=body.get("expired_at");Instant expiresAt=null;
        if(expiry!=null&&!expiry.isNull()) {
            if(!expiry.isString())throw invalid();
            try {expiresAt=OffsetDateTime.parse(expiry.stringValue()).toInstant();}catch(java.time.DateTimeException badTime){throw invalid();}
            if(!expiresAt.isAfter(finish))throw invalid();
        }
        // Persist only allowlisted observations; never retain client_secret, metadata or raw response.
        var m=new TreeMap<String,Object>();m.put("version",1);m.put("flowId",e.flowId());m.put("amount",e.amount());
        m.put("status","requires_capture");m.put("currency","jpy");m.put("captureMethod","manual");m.put("amountCapturable",e.amount());
        m.put("amountReceived",0);m.put("liveMode",mode.booleanValue());m.put("connection",e.connection());
        m.put("authorizationOperationId",e.authorizationOperationId()==null?null:e.authorizationOperationId().toString());
        m.put("expiredAt",expiresAt==null?null:expiresAt.toString());
        m.put("startedAt",start.toString());m.put("finishedAt",finish.toString());
        var result=new OrderAuthorizationObservation(e,start,finish,a,b,clock,ticker,JsonMapper.builder().build().writeValueAsString(m),expiresAt);
        result.fresh();return result;
    }
    private static String text(JsonNode n,String k){var v=n.get(k);if(v==null||!v.isString())throw invalid();return v.stringValue();}
    private static boolean number(JsonNode n,String k,long x){var v=n.get(k);return v!=null&&v.isIntegralNumber()&&v.canConvertToLong()&&v.longValue()==x;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Authorization observation rejected");}
    private void fresh() {
        long elapsed=ticker.getAsLong()-startNanos,duration=finishNanos-startNanos;Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if((expiresAt!=null&&!expiresAt.isAfter(now))||duration<0||elapsed<duration||elapsed>MAX_AGE.toNanos()||finished.isBefore(started)||now.isBefore(finished)
            ||Duration.between(started,now).compareTo(MAX_AGE)>0)throw new IllegalStateException("Authorization observation expired");
    }
    public void consume(Expected current) {if(!used.compareAndSet(false,true))throw new IllegalStateException("Observation already consumed");requireConsumed(current);}
    public void requireConsumed(Expected current) {if(!used.get()||!expected.equals(current))throw new IllegalStateException("Observation target changed");fresh();}
    public String payload(){return payload;}
    public Instant startedAt(){return started;}
    public Instant finishedAt(){return finished;}
    @Override public String toString(){return "OrderAuthorizationObservation[redacted]";}
}
