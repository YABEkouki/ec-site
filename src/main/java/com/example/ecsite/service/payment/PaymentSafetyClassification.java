package com.example.ecsite.service.payment;

import java.util.List;
import com.example.ecsite.entity.PaymentTransactionPurpose;
import java.time.LocalDateTime;
import java.util.Objects;
import com.example.ecsite.service.payment.PaymentSafetySnapshot.*;

/** Persisted-reference classification only: neither live authorization nor financial recovery is proven. */
public record PaymentSafetyClassification(
        ActivePaymentDisposition disposition, Long activePaymentId,
        List<TransactionPurpose> transactionPurposes, List<ReviewIssue> reviewIssues,
        ClassificationEvidence evidence) {
    public PaymentSafetyClassification {
        Objects.requireNonNull(disposition);
        Objects.requireNonNull(evidence);
        transactionPurposes = List.copyOf(transactionPurposes);
        reviewIssues = List.copyOf(reviewIssues);
        if ((disposition == ActivePaymentDisposition.SELECTED) != (activePaymentId != null)
                || (disposition == ActivePaymentDisposition.REVIEW) != !reviewIssues.isEmpty()) {
            throw new IllegalArgumentException("Inconsistent classification");
        }
    }

    public enum ActivePaymentDisposition { SELECTED, NORMAL_NULL, REVIEW }
    public enum FinancialResumption { NOT_EVALUATED }
    public FinancialResumption financialResumption() { return FinancialResumption.NOT_EVALUATED; }

    public enum PurposeDisposition { CONFIRMED, UNKNOWN, CONFLICT }

    /** One entry per supplied row; inputIndex disambiguates even duplicate/null transaction IDs. */
    public record TransactionPurpose(int inputIndex, Long transactionId, PurposeDisposition disposition,
            PaymentTransactionPurpose purpose, String declaredPurpose, String evidence) {
        public TransactionPurpose {
            Objects.requireNonNull(disposition);
            Objects.requireNonNull(purpose);
            if (inputIndex < 0 || (disposition == PurposeDisposition.CONFIRMED) == (purpose == PaymentTransactionPurpose.LEGACY_UNKNOWN)) {
                throw new IllegalArgumentException("Unconfirmed purpose must remain LEGACY_UNKNOWN");
            }
        }
    }
    public enum TimeComparisonOutcome { ORDERED, INVERTED, NOT_COMPARABLE }
    public enum TimeComparisonReason {
        SAME_WALL_CLOCK_AND_ZONE, TRANSACTION_START_IS_NOT_EVENT_TIME,
        UNKNOWN_CLOCK, DIFFERENT_CLOCK_OR_ZONE, INVALID_CLOCK_METADATA, LOCAL_TIME_NOT_UNIQUE
    }
    public record TimeComparison(TimeReference earlier, LocalDateTime earlierValue, TimeOrigin earlierOrigin,
            TimeReference later, LocalDateTime laterValue, TimeOrigin laterOrigin,
            TimeComparisonOutcome outcome, TimeComparisonReason reason) {}
    public record ClassificationEvidence(ShippingPreparationFacts shippingPreparation, List<TimeComparison> timeComparisons) {
        public ClassificationEvidence { timeComparisons = List.copyOf(timeComparisons); }
    }
    public record ReviewIssue(Reason reason, Long paymentId, Long transactionId, String evidence) {}
    public enum Reason {
        INVALID_INPUT, INCOMPLETE_HISTORY, ORDER_HISTORY_MISMATCH, INVALID_PAYMENT,
        UNKNOWN_TRANSACTION, UNSUPPORTED_PURPOSE, AMBIGUOUS_PAYMENT, AUTHORIZATION_MISMATCH,
        PENDING_CONFLICT, INCONSISTENT_STATE, SHIPMENT_MISMATCH, CAPTURE_REQUEST_UNVERIFIED, TEMPORAL_CONTRADICTION
    }
}
