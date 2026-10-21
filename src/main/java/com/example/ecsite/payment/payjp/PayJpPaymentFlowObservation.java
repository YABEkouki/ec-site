package com.example.ecsite.payment.payjp;

import java.time.OffsetDateTime;
import java.util.Map;
import com.fasterxml.jackson.annotation.*;

/** Nullable provider observations, never an authorization to Capture/Cancel or switch an order.
 * expired_at semantics are not assumed to be an authorization-valid-until guarantee. */
@JsonIgnoreProperties(ignoreUnknown=true)
public record PayJpPaymentFlowObservation(String id,String status,Long amount,String currency,
    @JsonProperty("amount_received") Long amountReceived,
    @JsonProperty("amount_capturable") Long amountCapturable,
    @JsonProperty("capture_method") String captureMethod,Boolean livemode,
    @JsonProperty("expired_at") OffsetDateTime expiredAt,
    @JsonProperty("canceled_at") OffsetDateTime canceledAt,Map<String,Object> metadata){
    public PayJpPaymentFlowObservation{
        if(metadata!=null)metadata=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }
    @Override public String toString(){return "PayJpPaymentFlowObservation[redacted]";}
}
