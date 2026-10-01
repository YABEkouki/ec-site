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
        PayJpPaymentErrorResponse lastPaymentError) {
}
