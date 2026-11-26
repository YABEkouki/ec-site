package com.example.ecsite.payment.payjp;

import java.time.OffsetDateTime;

/**
 * Feature144: what a GET of an order change's new Payment Flow says about its cancel by expiry. Only the fields the
 * closure checks; never card data, client secret or the response body.
 */
public record ExpiredFlowObservation(String id, String status, String cancellationReason, Long amount, String currency,
        String captureMethod, Boolean livemode, Long amountReceived, Long amountCapturable, OffsetDateTime expiredAt,
        OffsetDateTime canceledAt, String orderId, String operationId) {

    /** Canceled by PAY.JP because the authorization expired, with nothing received or capturable. */
    public boolean expired() {
        return "canceled".equals(status) && "expired".equals(cancellationReason) && expiredAt != null && canceledAt != null
                && !expiredAt.isAfter(canceledAt) && (amountReceived == null || amountReceived == 0L)
                && (amountCapturable == null || amountCapturable == 0L);
    }

    @Override public String toString() { return "ExpiredFlowObservation[redacted]"; }
}
