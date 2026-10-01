package com.example.ecsite.payment;

public interface PaymentGateway {

    AuthorizationPreparation prepareAuthorization(AuthorizationRequest request);

    AuthorizationResult retrieveAuthorization(String providerPaymentId);

    CancellationResult cancelAuthorization(CancellationRequest request);
}
