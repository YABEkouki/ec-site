package com.example.ecsite.service.payment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Detached, immutable persisted facts for ONE order. No entities or provider observations.
 * Raw enum names preserve unknown legacy values. The future adapter must supply ALL rows,
 * consistently read under Order -> Payment locks; an omitted row cannot be discovered here.
 * LocalDateTime uses the existing persisted timestamp convention, without reading a clock.
 */
public record PaymentSafetySnapshot(
        OrderFacts order, List<PaymentFacts> payments, List<TransactionFacts> transactions,
        List<StatusHistory> statusHistory, List<CaptureEvidence> captureEvidence,
        boolean complete, Map<TimeReference, TimeOrigin> timeOrigins) {
    public PaymentSafetySnapshot {
        payments = List.copyOf(payments);
        transactions = List.copyOf(transactions);
        statusHistory = List.copyOf(statusHistory);
        captureEvidence = List.copyOf(captureEvidence);
        timeOrigins = Map.copyOf(timeOrigins);
    }

    /** Without explicit provenance, timestamps are retained but not assumed comparable. */
    public PaymentSafetySnapshot(OrderFacts order, List<PaymentFacts> payments, List<TransactionFacts> transactions,
            List<StatusHistory> statusHistory, List<CaptureEvidence> captureEvidence, boolean complete) {
        this(order, payments, transactions, statusHistory, captureEvidence, complete, Map.of());
    }

    public enum TimeField {
        ORDER_PAID, ORDER_CANCELLED, ORDER_SHIPPED, PREPARATION_STARTED,
        TRANSACTION_CREATED, TRANSACTION_COMPLETED, HISTORY_CHANGED
    }
    public enum ClockKind { DATABASE_TRANSACTION_START, DATABASE_WALL_CLOCK, APPLICATION_WALL_CLOCK, UNKNOWN }
    public record TimeReference(TimeField field, Long rowId) {}

    /**
     * Trusted adapter evidence, never guessed from a column name or timestamp value.
     * clockDomain identifies an actually shared clock, not merely a deployment/provider.
     * Different JVM clocks must not be labelled as the same domain without proof.
     * Transaction-start readings do not establish lifecycle event order after lock waits.
     */
    public record TimeOrigin(ClockKind kind, String clockDomain, String zoneId) {
        public static TimeOrigin unknown() { return new TimeOrigin(ClockKind.UNKNOWN, null, null); }
    }

    public record ShippingPreparationFacts(LocalDateTime startedAt, Long actorId, String actorUsername) {
        public boolean complete() {
            return startedAt != null && actorId != null && actorId > 0 && actorUsername != null
                    && !actorUsername.isBlank() && actorUsername.length() <= 100;
        }
        public boolean absent() { return startedAt == null && actorId == null && actorUsername == null; }
    }

    public record OrderFacts(Long id, String status, int totalAmount, int contentRevision,
            LocalDateTime paidAt, LocalDateTime cancelledAt, LocalDateTime shippedAt,
            ShippingPreparationFacts shippingPreparation, Long shipmentCapturePaymentId,
            Long shippedByAccountId, String shippedByUsername) {
        public boolean shippingPreparationComplete() {
            return shippingPreparation != null && shippingPreparation.complete();
        }
    }

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
