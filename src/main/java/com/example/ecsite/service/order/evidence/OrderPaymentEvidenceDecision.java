package com.example.ecsite.service.order.evidence;

import java.util.UUID;

/** Local evidence classification, never an execution permit or a provider-validity assertion. */
public record OrderPaymentEvidenceDecision(Disposition disposition, Reason reason, long orderId, Long paymentId,
        Long authorizationId, UUID epochId, Long headHistoryId, int authorizedAmount) {
    public enum Disposition { EVIDENCE_CURRENT, EVIDENCE_RETAINED_AUTH, LEGACY_UNENROLLED, BLOCKED, REVIEW_REQUIRED }
    public enum Reason { NONE, INVALID_CONTENT, FINANCIAL_STATE, UNRESOLVED_OPERATION, MISSING_EVIDENCE,
        LEGACY_REQUIRES_ADMISSION, INVALID_EPOCH, INVALID_CHAIN, CURRENT_MISMATCH, AMOUNT_EXCEEDS_AUTHORIZATION }
    public boolean requiresExternalVerification() { return true; }
    public boolean locallyVerified() { return disposition==Disposition.EVIDENCE_CURRENT || disposition==Disposition.EVIDENCE_RETAINED_AUTH; }
}
