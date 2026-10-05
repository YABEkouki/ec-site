package com.example.ecsite.payment.payjp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PayJpWebhookEvent(
        String id,
        String type,
        PayJpWebhookPaymentFlow data) {
}
