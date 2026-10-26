package com.example.ecsite.payment;

public interface PaymentGateway {

    /** Trusted GET-only authorization observation for internal NEW_ABORT preparation. */
    default com.example.ecsite.payment.payjp.AbortPreparationObservation observeAbortPreparation(
            com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected expected){
        throw new UnsupportedOperationException("Abort preparation observation is not supported");
    }

    /** Trusted GET-only observation of the saved old authorization. */
    default com.example.ecsite.payment.payjp.OldAuthorizationObservation observeOldAuthorization(
            com.example.ecsite.payment.payjp.OldAuthorizationObservation.Expected expected){
        throw new UnsupportedOperationException("Old authorization observation is not supported");
    }

    /** Internal GET-only closure observation, disconnected from result writers. */
    default com.example.ecsite.payment.payjp.NewFlowClosureObservation observeNewFlowClosure(
            com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected expected){
        throw new UnsupportedOperationException("New Flow closure observation is not supported");
    }

    /** Explicit internal GET-only assessment. No financial sender or automatic worker is connected. */
    default com.example.ecsite.payment.payjp.NewFlowClosureObservation.Assessment assessNewFlowClosure(
            com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected expected){
        throw new UnsupportedOperationException("New Flow closure assessment is not supported");
    }

    /** Dedicated opaque one-use token only. A response never establishes financial closure. */
    default void sendRequestedAbort(com.example.ecsite.service.orderchange.RequestedAbortDispatchControl.Dispatch dispatch){
        throw new UnsupportedOperationException("Requested abort transport is not supported");
    }
    /** Internal NEW_ABORT GET only; no dispatch or financial state mutation. */
    default com.example.ecsite.payment.payjp.RequestedAbortObservation observeRequestedAbort(
            com.example.ecsite.payment.payjp.RequestedAbortObservation.Expected expected){
        throw new UnsupportedOperationException("Requested abort observation is not supported");
    }

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
