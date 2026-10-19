package com.example.ecsite.payment.capture;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import com.example.ecsite.entity.*;

/** Persisted identity and validated HTTP data. This is NOT a POST execution permit.
 * Time validation is structural: an old request remains readable for audit.
 */
public record StoredCaptureRequest(long id, long paymentId, long captureTransactionId, int orderContentRevision,
        CaptureHttpRequest httpRequest, Instant createdAt, Instant replayNotAfter) {
    public StoredCaptureRequest {
        if (id <= 0) throw new IllegalArgumentException("Invalid stored Capture request identifier");
        validateMetadata(paymentId, captureTransactionId, orderContentRevision, httpRequest, createdAt, replayNotAfter);
    }
    static void validateMetadata(long paymentId, long captureTransactionId, int orderContentRevision,
            CaptureHttpRequest httpRequest, Instant createdAt, Instant replayNotAfter) {
        if (paymentId <= 0 || captureTransactionId <= 0 || orderContentRevision < 0 || httpRequest == null
                || createdAt == null || replayNotAfter == null || createdAt.getNano() % 1000 != 0
                || replayNotAfter.getNano() % 1000 != 0 || !replayNotAfter.isAfter(createdAt)
                || Duration.between(createdAt, replayNotAfter).compareTo(Duration.ofHours(23)) > 0) {
            throw new IllegalArgumentException("Invalid stored Capture request metadata");
        }
    }
    /** Caller supplies latest locked entities in B2; no refresh, eligibility check or permission is performed here. */
    public void validateAgainst(Payment payment, PaymentTransaction capture, CaptureConnectionIdentity expected) {
        validateTarget(payment, capture, httpRequest, expected);
        if (paymentId != payment.getId() || captureTransactionId != capture.getId()
                || orderContentRevision != capture.getOrderContentRevision()) {
            throw new IllegalArgumentException("Stored Capture request target mismatch");
        }
    }
    static void validateTarget(Payment payment, PaymentTransaction capture, CaptureHttpRequest request,
            CaptureConnectionIdentity expected) {
        if (payment == null || capture == null || request == null || expected == null
                || payment.getId() == null || payment.getId() <= 0 || capture.getId() == null || capture.getId() <= 0
                || payment.getOrder() == null || payment.getOrder().getId() == null || payment.getOrder().getId() <= 0
                || capture.getPayment() == null || !Objects.equals(payment.getId(), capture.getPayment().getId())
                || capture.getPayment().getOrder() == null
                || !Objects.equals(payment.getOrder().getId(), capture.getPayment().getOrder().getId())
                || capture.getTransactionType() != PaymentTransactionType.CAPTURE
                || payment.getProvider() != PaymentProvider.PAYJP || payment.getPaymentMethod() != PaymentMethod.CARD
                || !request.flowId().equals(payment.getProviderPaymentId())
                || request.amount() != capture.getAmount() || request.amount() != payment.getOrder().getTotalAmount()
                || capture.getOrderContentRevision() < 0
                || capture.getOrderContentRevision() != payment.getOrder().getContentRevision()
                || !request.idempotencyKey().equals(capture.getIdempotencyKey())
                || !request.connection().equals(expected)) {
            throw new IllegalArgumentException("Capture request identity or parameters do not match target");
        }
        // Payment.amount is the original authorization amount: a valid decrease must remain possible.
    }
    public byte[] body() { return httpRequest.body(); }
    public byte[] fingerprint() { return httpRequest.fingerprint(); }
    @Override public String toString() { return "StoredCaptureRequest[id=" + id + ", redacted]"; }
}
