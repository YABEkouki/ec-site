package com.example.ecsite.payment.payjp;

import java.time.OffsetDateTime;
import java.util.Map;
import com.fasterxml.jackson.annotation.*;
import tools.jackson.databind.JsonNode;

/** Nullable provider observations, never an authorization to Capture/Cancel or switch an order.
 * expired_at semantics are not assumed to be an authorization-valid-until guarantee. */
@JsonIgnoreProperties(ignoreUnknown=true)
public record PayJpPaymentFlowObservation(String id,String status,Long amount,String currency,
    @JsonProperty("amount_received") Long amountReceived,
    @JsonProperty("amount_capturable") Long amountCapturable,
    @JsonProperty("capture_method") String captureMethod,Boolean livemode,
    @JsonProperty("expired_at") OffsetDateTime expiredAt,
    @JsonProperty("canceled_at") OffsetDateTime canceledAt,Map<String,Object> metadata,
    @JsonIgnore boolean amountReceivedPresent){
    /** Pure raw-tree contract shared with the internal NEW_ABORT preparation validator.
     * This validates values only; it does not establish transport provenance or mint a permit. */
    public static void requireRequestedAbortAuthorization(
            com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence.Expected expected,JsonNode body){
            if(expected==null||body==null||!body.isObject())throw new IllegalArgumentException("Invalid abort authorization evidence");
            for(String key:java.util.List.of("id","status","currency","capture_method"))
                if(body.get(key)==null||!body.get(key).isString())throw new IllegalArgumentException("Invalid abort authorization evidence");
            var value=body.get("amount");var mode=body.get("livemode");var metadata=body.get("metadata");
            if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()!=expected.amount()
                ||mode==null||!mode.isBoolean()||mode.booleanValue()!=expected.connection().mode().equals("LIVE")
                ||metadata==null||!metadata.isObject())throw new IllegalArgumentException("Invalid abort authorization evidence");
            var orderValue=metadata.get("order_id");var operationValue=metadata.get("order_change_operation_id");
            if(orderValue==null||!orderValue.isString()||!Long.toString(expected.orderId()).equals(orderValue.stringValue())
                ||operationValue==null||!operationValue.isString()||!expected.operationId().toString().equals(operationValue.stringValue())
                ||!expected.newFlowId().equals(body.get("id").stringValue())||!"requires_capture".equals(body.get("status").stringValue())
                ||!"jpy".equals(body.get("currency").stringValue())||!"manual".equals(body.get("capture_method").stringValue()))
                throw new IllegalArgumentException("Invalid abort authorization evidence");
            var observed=PayJpPaymentFlowObservation.fromJson(expected.newFlowId(),"requires_capture",(long)expected.amount(),"jpy",
                body.get("amount_received"),body.get("amount_capturable"),"manual",mode.booleanValue(),null,null,Map.of());
            if(!observed.hasUncapturedAuthorization(expected.amount()))throw new IllegalArgumentException("Invalid abort authorization evidence");
    }
    public PayJpPaymentFlowObservation{
        if(metadata!=null)metadata=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }
    /** A Java caller supplying the received value explicitly also supplies its presence. */
    public PayJpPaymentFlowObservation(String id,String status,Long amount,String currency,Long amountReceived,
            Long amountCapturable,String captureMethod,Boolean livemode,OffsetDateTime expiredAt,
            OffsetDateTime canceledAt,Map<String,Object> metadata){
        this(id,status,amount,currency,amountReceived,amountCapturable,captureMethod,livemode,expiredAt,canceledAt,metadata,true);
    }
    @JsonCreator
    public static PayJpPaymentFlowObservation fromJson(@JsonProperty("id") String id,@JsonProperty("status") String status,
            @JsonProperty("amount") Long amount,@JsonProperty("currency") String currency,
            @JsonProperty("amount_received") JsonNode received,@JsonProperty("amount_capturable") JsonNode capturable,
            @JsonProperty("capture_method") String captureMethod,@JsonProperty("livemode") Boolean livemode,
            @JsonProperty("expired_at") OffsetDateTime expiredAt,@JsonProperty("canceled_at") OffsetDateTime canceledAt,
            @JsonProperty("metadata") Map<String,Object> metadata){
        return new PayJpPaymentFlowObservation(id,status,amount,currency,integerOrNull(received),integerOrNull(capturable),
            captureMethod,livemode,expiredAt,canceledAt,metadata,received!=null&&!received.isMissingNode());
    }
    private static Long integerOrNull(JsonNode value){
        if(value==null||value.isNull()||value.isMissingNode())return null;
        if(!value.isIntegralNumber()||!value.canConvertToLong())
            throw new IllegalArgumentException("Observed received/capturable amount must be a signed 64-bit integer or null");
        return value.longValue();
    }
    /** Evidence only: identity, purpose, lease/locks and durable Cancel decision remain caller responsibilities. */
    public boolean hasUncapturedAuthorization(int expectedAmount){
        return "requires_capture".equals(status)&&amount!=null&&amount==expectedAmount
            &&amountCapturable!=null&&amountCapturable==expectedAmount&&amountReceivedPresent
            &&(amountReceived==null||amountReceived==0L);
    }
    @Override public String toString(){return "PayJpPaymentFlowObservation[redacted]";}
}
