package com.example.ecsite.payment;

public record AuthorizationPreparation(
        String providerPaymentId,
        String clientSecret) {
}
