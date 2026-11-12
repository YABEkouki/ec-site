package com.example.ecsite.service.order.history;

import java.util.List;

/** Internal verification outcome. Not a customer response or a financial authorization. */
public record OrderChangeHistoryVerificationResult(OrderChangeEvidenceBundle.Status acquisitionStatus,
        Displayability displayability,List<HistoryDecision> histories,List<Issue> issues) {
    public enum Classification { FEATURE125_CONFIRMED, LEGACY_CONFIRMED, UNVERIFIED }
    public enum Displayability { ALL, PARTIAL, NONE }
    public enum Reason {
        LIMIT_EXCEEDED, ACQUISITION_CONTRACT, DUPLICATE_KEY, OWNERSHIP_MISMATCH,
        MISSING_REFERENCE, UNKNOWN_FORMAT, LEGACY_NOT_IMPLEMENTED, INCOMPLETE_APPLICATION,
        ADMISSION_PROOF, EXECUTION_SEQUENCE, EVENT_CHAIN, PAYLOAD_BINDING,
        SNAPSHOT_CHAIN, FINANCIAL_EVIDENCE, HISTORY_DIFFERENCE, CONTRADICTORY_CLOSURE,
        APPLIED_WITHOUT_HISTORY, DEPENDENCY_UNPROVEN
    }
    public record Issue(Reason reason,OrderChangeEvidenceBundle.EvidenceSet source,String reference) {}
    public record HistoryDecision(long historyId,Classification classification,
            VerifiedOrderChange change,List<Reason> reasons) {
        public HistoryDecision {
            reasons=List.copyOf(reasons);
            if((classification==Classification.UNVERIFIED)!=(change==null))throw new IllegalArgumentException("Invalid decision");
        }
    }
    public OrderChangeHistoryVerificationResult {histories=List.copyOf(histories);issues=List.copyOf(issues);}
    public List<VerifiedOrderChange> verifiedChanges(){return histories.stream().map(HistoryDecision::change).filter(java.util.Objects::nonNull).toList();}
}
