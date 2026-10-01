package com.example.ecsite.service.payment;

public record PaymentAuthorizationPreparation(
        Long paymentId,
        String clientSecret) {
}
