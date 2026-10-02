package com.example.ecsite.service.payment;

import com.example.ecsite.payment.AuthorizationResult;

public record PaymentAuthorizationRecovery(
        Long paymentId,
        AuthorizationResult result,
        String clientSecret,
        PaymentAuthorizationRecoveryAction action) {
}
