package com.example.ecsite.payment.payjp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PayJpPaymentFlowResponse(
        String id,
        @JsonProperty("client_secret")
        String clientSecret,
        String status,
        @JsonProperty("last_payment_error")
        PayJpPaymentErrorResponse lastPaymentError,
        /** Feature135: authorization expiry of an uncaptured Flow; absent or null is allowed. */
        @JsonProperty("expired_at")
        java.time.OffsetDateTime expiredAt) {

    public PayJpPaymentFlowResponse(String id, String clientSecret, String status,
            PayJpPaymentErrorResponse lastPaymentError) {
        this(id, clientSecret, status, lastPaymentError, null);
    }
}
