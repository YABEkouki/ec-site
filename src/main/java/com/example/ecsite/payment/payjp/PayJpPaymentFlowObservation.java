package com.example.ecsite.payment.payjp;

import java.time.OffsetDateTime;
import java.util.Map;
import com.fasterxml.jackson.annotation.*;

/** Nullable provider observations, never an authorization to Capture/Cancel or switch an order.
 * expired_at semantics are not assumed to be an authorization-valid-until guarantee. */
@JsonIgnoreProperties(ignoreUnknown=true)
public record PayJpPaymentFlowObservation(String id,String status,Long amount,String currency,
    @JsonProperty("amount_received")
    @tools.jackson.databind.annotation.JsonDeserialize(using=ReceivedAmountDeserializer.class) Long amountReceived,
    @JsonProperty("amount_capturable") Long amountCapturable,
    @JsonProperty("capture_method") String captureMethod,Boolean livemode,
    @JsonProperty("expired_at") OffsetDateTime expiredAt,
    @JsonProperty("canceled_at") OffsetDateTime canceledAt,Map<String,Object> metadata){
    public PayJpPaymentFlowObservation{
        if(metadata!=null)metadata=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }
    /** JSON null and an absent field both map to null (waiting only); invalid types are rejected. */
    public static final class ReceivedAmountDeserializer extends tools.jackson.databind.ValueDeserializer<Long>{
        @Override public Long deserialize(tools.jackson.core.JsonParser parser,tools.jackson.databind.DeserializationContext context){
            if(parser.currentToken()!=tools.jackson.core.JsonToken.VALUE_NUMBER_INT)
                return context.reportInputMismatch(Long.class,"amount_received must be an integer or null");
            return parser.getLongValue();
        }
    }
    @Override public String toString(){return "PayJpPaymentFlowObservation[redacted]";}
}
