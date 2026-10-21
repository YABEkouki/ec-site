package com.example.ecsite.payment;

public interface PaymentGateway {

    default com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation observeOrderChangeFlow(String flowId) {
        throw new UnsupportedOperationException("Order change observation is not supported");
    }
    default com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation sendOrderChangeRequest(
            com.example.ecsite.payment.orderchange.OrderChangeRequestSender.Dispatch dispatch) {
        throw new UnsupportedOperationException("Order change transport is not supported");
    }

    AuthorizationPreparation prepareAuthorization(AuthorizationRequest request);

    AuthorizationRecovery retrieveAuthorization(String providerPaymentId);

    PaymentFlowState retrievePaymentFlow(String providerPaymentId);

    CancellationResult cancelAuthorization(CancellationRequest request);

    CaptureResult capture(com.example.ecsite.payment.capture.CaptureHttpRequest request);
}
