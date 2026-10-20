package com.example.ecsite.service.payment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detached, immutable persisted facts for ONE order. No entities or provider observations.
 * Raw enum names preserve unknown legacy values. The future adapter must supply ALL rows,
 * consistently read under Order -> Payment locks; an omitted row cannot be discovered here.
 * LocalDateTime uses the existing persisted timestamp convention, without reading a clock.
 */
public record PaymentSafetySnapshot(
        OrderFacts order, List<PaymentFacts> payments, List<TransactionFacts> transactions,
        List<StatusHistory> statusHistory, List<CaptureEvidence> captureEvidence,
        boolean complete) {
    public PaymentSafetySnapshot {
        payments = List.copyOf(payments);
        transactions = List.copyOf(transactions);
        statusHistory = List.copyOf(statusHistory);
        captureEvidence = List.copyOf(captureEvidence);
    }

    public record OrderFacts(Long id, String status, int totalAmount, int contentRevision,
            LocalDateTime paidAt, LocalDateTime cancelledAt, LocalDateTime shippedAt,
            boolean shippingPreparationComplete, Long shipmentCapturePaymentId,
            Long shippedByAccountId, String shippedByUsername) {}

    public record PaymentFacts(Long id, Long orderId, String provider, String paymentMethod,
            String status, int amount, String providerPaymentId) {}

    /** declaredPurpose is null for pre-purpose rows; unrecognised values must not be inferred. */
    public record TransactionFacts(Long id, Long paymentId, String type, String status,
            int amount, int contentRevision, String idempotencyKey, String providerTransactionId,
            LocalDateTime createdAt, LocalDateTime completedAt, String initiatorType,
            Long initiatorId, String initiatorUsername, String declaredPurpose) {}

    public record StatusHistory(Long id, Long orderId, String fromStatus, String toStatus,
            LocalDateTime changedAt, String actorType, Long actorId, String actorUsername) {}

    public enum CaptureVerification { VERIFIED, MISSING, MISMATCH, UNVERIFIED }

    /**
     * VERIFIED is supplied only after the V54 validator has checked the stored immutable
     * request (including bytes, fingerprint and connection identity). It is not a replay permit.
     * These identity fields are independently cross-checked here, never reconstructed as proof.
     */
    public record CaptureEvidence(Long transactionId, Long paymentId, String providerPaymentId,
            int amount, int contentRevision, String idempotencyKey, CaptureVerification verification) {}
}
