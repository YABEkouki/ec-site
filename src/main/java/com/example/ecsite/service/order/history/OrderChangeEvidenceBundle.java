package com.example.ecsite.service.order.history;

import java.util.*;

/** Detached, UNVERIFIED database facts. COMPLETE certifies acquisition, never business completion. */
public record OrderChangeEvidenceBundle(Status status, OrderEvidence order,
        Map<EvidenceSet,List<EvidenceRow>> evidence, Coverage coverage, List<Diagnostic> diagnostics) {
    public enum Status { COMPLETE, LIMIT_EXCEEDED }
    public enum EvidenceSet {
        HISTORIES, ITEM_CHANGES, CHARGE_CHANGES, OPERATIONS, OPERATION_ITEMS,
        ADMISSIONS, ADMISSION_PROOFS, CONTROLS, ATTEMPTS, RESULTS, EVENTS, EPOCHS,
        PAYMENTS, TRANSACTIONS, CAPTURE_REQUESTS, LEGACY_CLOSURE_ATTEMPTS, LEGACY_CLOSURE_ISSUES, LEGACY_CLOSURE_EVENTS, FINANCIAL_REQUESTS, DISPATCHES, RECOVERY_AUDITS,
        UNAPPLIED, FAILURE_CONTROLS, FAILURE_OBSERVATIONS, FAILURE_UNKNOWN_RESOLUTIONS,
        FAILURE_BUSINESS_CLOSURES, FAILURE_WAITING_RESOLUTIONS, GENERATIONS, INVENTORY_PLANS, RESERVATIONS
    }
    public record OrderEvidence(long orderId,long customerId,int revision,String epochId,Long headHistoryId) {}
    public record Coverage(long rows,long rawBytes) {}
    public record Diagnostic(String code,EvidenceSet source,String rowId,String field) {}
    public record BinaryEvidence(byte[] bytes) {
        public BinaryEvidence { bytes=Objects.requireNonNull(bytes).clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
        @Override public String toString(){return "BinaryEvidence[redacted]";}
    }
    /** Scalar strings preserve unknown enum values and SQL NULL; binaries remain exact bytes. */
    public record EvidenceRow(Map<String,String> values,Map<String,BinaryEvidence> binaries) {
        public EvidenceRow {
            values=Collections.unmodifiableMap(new LinkedHashMap<>(values));
            binaries=Collections.unmodifiableMap(new LinkedHashMap<>(binaries));
        }
        public String value(String name){return values.get(name);}
        @Override public String toString(){return "EvidenceRow[redacted]";}
    }
    public OrderChangeEvidenceBundle {
        Objects.requireNonNull(status);Objects.requireNonNull(order);Objects.requireNonNull(coverage);
        var copied=new EnumMap<EvidenceSet,List<EvidenceRow>>(EvidenceSet.class);
        evidence.forEach((key,rows)->copied.put(key,List.copyOf(rows)));
        if(status==Status.LIMIT_EXCEEDED&&!copied.isEmpty())throw new IllegalArgumentException("Incomplete acquisition cannot carry usable evidence");
        evidence=Collections.unmodifiableMap(copied);diagnostics=List.copyOf(diagnostics);
    }
    public List<EvidenceRow> rows(EvidenceSet set){return evidence.getOrDefault(set,List.of());}
    @Override public String toString(){return "OrderChangeEvidenceBundle["+status+"]";}
}
