package com.example.ecsite.service.payment;

import java.util.List;
import com.example.ecsite.entity.PaymentTransactionPurpose;

/** Persisted-reference classification only: neither live authorization nor financial recovery is proven. */
public record PaymentSafetyClassification(
        ActivePaymentDisposition disposition, Long activePaymentId,
        List<TransactionPurpose> transactionPurposes, List<ReviewIssue> reviewIssues) {
    public PaymentSafetyClassification {
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

    public record TransactionPurpose(Long transactionId, PaymentTransactionPurpose purpose,
            String evidence) {}
    public record ReviewIssue(Reason reason, Long paymentId, Long transactionId, String evidence) {}
    public enum Reason {
        INVALID_INPUT, INCOMPLETE_HISTORY, ORDER_HISTORY_MISMATCH, INVALID_PAYMENT,
        UNKNOWN_TRANSACTION, UNSUPPORTED_PURPOSE, AMBIGUOUS_PAYMENT, AUTHORIZATION_MISMATCH,
        PENDING_CONFLICT, INCONSISTENT_STATE, SHIPMENT_MISMATCH, CAPTURE_REQUEST_UNVERIFIED
    }
}
