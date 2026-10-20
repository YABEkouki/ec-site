package com.example.ecsite.service.payment.safety;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeReference;
import com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeOrigin;

/** Complete detached raw row sets, NOT a classification, V54 verification or execution permit.
 * TIMESTAMP is local date/time; TIMESTAMPTZ is the persisted instant, without an invented zone.
 * Absent provenance remains absent/unknown. The active pointer is observation only.
 */
public record PaymentSafetyReadFacts(OrderRow order, List<PaymentRow> payments,
        List<TransactionRow> transactions, List<HistoryRow> statusHistory, List<CaptureRow> captureRequests,
        Map<String,String> verificationProfiles, Map<TimeReference,TimeOrigin> timeOrigins) {
    public PaymentSafetyReadFacts {
        Objects.requireNonNull(order);
        payments=List.copyOf(payments); transactions=List.copyOf(transactions);
        statusHistory=List.copyOf(statusHistory); captureRequests=List.copyOf(captureRequests);
        verificationProfiles=Map.copyOf(verificationProfiles); timeOrigins=Map.copyOf(timeOrigins);
    }
    public record OrderRow(Long id, Long userId, String status, Integer totalAmount,
            Integer itemSubtotal, Integer chargeTotal, Integer taxAmount, Integer contentRevision,
            LocalDateTime orderedAt, LocalDateTime changeDeadlineAt, LocalDateTime paidAt,
            LocalDateTime cancelledAt, LocalDateTime shippedAt, LocalDateTime shippingPreparationStartedAt,
            Long shippingPreparationStartedByAccountId, String shippingPreparationStartedByUsername,
            Long shippedByAccountId, String shippedByUsername, Long shipmentCapturePaymentId, Long activePaymentId) {}
    public record PaymentRow(Long id, Long orderId, String provider, String paymentMethod, String status,
            Integer amount, String providerPaymentId, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record TransactionRow(Long id, Long paymentId, String transactionType, String status, Integer amount,
            Integer orderContentRevision, String idempotencyKey, String providerTransactionId, String failureCode,
            String failureMessage, LocalDateTime createdAt, LocalDateTime completedAt, String initiatorType,
            Long initiatorId, String initiatorUsername, String internalNote, String purpose) {}
    public record HistoryRow(Long id, Long orderId, String fromStatus, String toStatus,
            LocalDateTime changedAt, String changedByType, Long changedByAccountId, String changedByUsername, String internalNote) {}
    public record CaptureRow(Long id, Long paymentId, Long captureTransactionId, String transactionType,
            String provider, String paymentMethod, String providerFlowId, String connectionId,
            String providerAccountRef, String mode, String apiOrigin, String httpMethod, String apiPath,
            String contentType, Integer amountToCapture, Integer orderContentRevision, String idempotencyKey,
            Short bodyFormatVersion, Bytes requestBody, Short fingerprintVersion, Bytes requestFingerprint,
            Instant createdAt, Instant replayNotAfter) {}

    /** Value-semantic, defensively copied binary data. No body or hash bytes in diagnostics. */
    public static final class Bytes {
        private final byte[] value;
        public Bytes(byte[] value) { this.value=Objects.requireNonNull(value).clone(); }
        public byte[] value() { return value.clone(); }
        public int length() { return value.length; }
        void writeTo(OutputStream out) throws IOException { out.write(value); }
        @Override public boolean equals(Object other) { return other instanceof Bytes b && Arrays.equals(value,b.value); }
        @Override public int hashCode() { return Arrays.hashCode(value); }
        @Override public String toString() { return "Bytes[length="+value.length+"]"; }
    }
}
