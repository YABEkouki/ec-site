package com.example.ecsite.service.order.evidence;

import java.util.List;
import java.util.UUID;

/** Detached V1 evidence carriers. No persistence mapping or permission to call a gateway.
 * Readers must supply complete payment, authorization and epoch-history sets from one consistent snapshot.
 * Pending non-AUTHORIZE work is represented by pendingFinancialOperation. These facts do not prove
 * external freshness, deadline admission, inventory, or historic legitimacy before a legacy checkpoint. */
public final class OrderPaymentEvidence {
    private OrderPaymentEvidence() {}
    public enum Origin { INITIAL_AUTH, REAUTH_COMPLETED, LEGACY_CURRENT_CHECKPOINT }

    public record Payment(long id, long orderId, String provider, String method, String status, int amount, String flowId) {}
    public record Authorization(long id, long orderId, long paymentId, String type, String status, int amount,
            int revision, String flowId, String purpose, UUID operationId) {}

    /** Supporting stored completion facts, not a reconstructed operation or provider observation. */
    public record Reauthorization(UUID operationId, long orderId, long oldPaymentId, long newPaymentId,
            long authorizationId, long historyId, int baseRevision, int afterAmount, String state,
            long oldCancelId, String oldCancelStatus, int oldCancelAmount, int oldCancelRevision, String oldCancelFlow) {}

    /** Opaque digests of an already recorded local assessment/observation. The pure validator only
     * checks binding/shape; it never treats them as proof of present external authorization. */
    public record Checkpoint(long orderId, long paymentId, long authorizationId, int revision,
            String contentFingerprint, String localAssessmentDigest, String externalObservationDigest) {}

    public record Epoch(int version, UUID id, long orderId, long paymentId, long authorizationId,
            int authorizedAmount, int authorizationRevision, Origin origin,
            OrderContentSnapshot start, Reauthorization reauthorization, Checkpoint checkpoint, String digest) {
        @Override public String toString() { return "OrderEvidenceEpoch[redacted]"; }
    }
    public record Change(int version, long id, long orderId, UUID operationId, UUID epochId, Long previousHistoryId,
            int beforeRevision, int beforeAmount, String beforeFingerprint, OrderContentSnapshot after,
            String previousDigest, String digest) {
        @Override public String toString() { return "OrderEvidenceChange[redacted]"; }
    }
    public record Facts(OrderContentSnapshot current, Long activePaymentId, List<Payment> payments,
            List<Authorization> authorizations, boolean evidenceRequired, Epoch epoch, List<Change> histories,
            Long headHistoryId, boolean unresolvedOperation, boolean pendingFinancialOperation) {
        public Facts {
            payments=List.copyOf(payments); authorizations=List.copyOf(authorizations); histories=List.copyOf(histories);
        }
        @Override public String toString() { return "OrderPaymentEvidenceFacts[redacted]"; }
    }
}
