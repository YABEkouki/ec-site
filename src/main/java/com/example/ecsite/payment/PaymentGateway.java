package com.example.ecsite.payment;

public interface PaymentGateway {

    AuthorizationPreparation prepareAuthorization(AuthorizationRequest request);

    AuthorizationRecovery retrieveAuthorization(String providerPaymentId);

    PaymentFlowState retrievePaymentFlow(String providerPaymentId);

    CancellationResult cancelAuthorization(CancellationRequest request);

    CaptureResult capture(CaptureRequest request);
}
