package com.example.ecsite.payment.payjp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PayJpPaymentErrorResponse(
        String code,
        String message) {
}
