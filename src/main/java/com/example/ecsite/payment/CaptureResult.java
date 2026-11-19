package com.example.ecsite.payment;

public record CaptureResult(
        CaptureResultStatus status,
        String providerTransactionId,
        String failureCode,
        String failureMessage,
        /** Feature135-B2: set only for a capture failed because PAY.JP canceled the Flow. */
        String cancellationReason) {

    /** Failure code of a shipment capture whose Payment Flow PAY.JP has canceled (V95). */
    public static final String FLOW_CANCELED = "payment_flow_canceled";

    public CaptureResult(CaptureResultStatus status, String providerTransactionId, String failureCode, String failureMessage) {
        this(status, providerTransactionId, failureCode, failureMessage, null);
    }

    /** Feature135-B2: a Flow read by GET. succeeded is captured, canceled can never be captured, anything else waits. */
    public static CaptureResult fromFlowState(PaymentFlowState state) {
        if (state.status() == PaymentFlowStatus.SUCCEEDED)
            return new CaptureResult(CaptureResultStatus.CAPTURED, state.providerPaymentId(), null, null);
        if (state.status() == PaymentFlowStatus.CANCELED) {
            String reason = state.cancellationReason() != null && state.cancellationReason().matches("[a-z][a-z0-9_]{0,39}")
                    ? state.cancellationReason() : "unknown";
            return new CaptureResult(CaptureResultStatus.FAILED, state.providerPaymentId(), FLOW_CANCELED,
                    "PAY.JP上で与信が取り消されているため、売上確定できません（理由: " + reason + "）。", reason);
        }
        return new CaptureResult(CaptureResultStatus.PENDING, state.providerPaymentId(), null, null);
    }

    public boolean flowCanceled() {
        return status == CaptureResultStatus.FAILED && FLOW_CANCELED.equals(failureCode);
    }
}
