package com.example.ecsite.service.order.history;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;

class OrderChangeHistoryVerifierTest {
    private final OrderChangeHistoryVerifier verifier = new OrderChangeHistoryVerifier();
    static OrderChangeEvidenceBundle empty() {
        var sets = new EnumMap<EvidenceSet,List<EvidenceRow>>(EvidenceSet.class);
        for(var set:EvidenceSet.values()) sets.put(set,List.of());
        return new OrderChangeEvidenceBundle(Status.COMPLETE,new OrderEvidence(1,2,0,null,null),sets,new Coverage(0,0),List.of());
    }
    @Test void completeEmptyAcquisitionIsNotAnError() {
        var result=verifier.verify(empty());
        assertEquals(OrderChangeHistoryVerificationResult.Displayability.ALL,result.displayability());
        assertTrue(result.histories().isEmpty());
    }
    @Test void missingCollectionIsNotEmptyHistory() {
        var b=empty();var sets=new EnumMap<>(b.evidence());sets.remove(EvidenceSet.RESULTS);
        var result=verifier.verify(new OrderChangeEvidenceBundle(b.status(),b.order(),sets,b.coverage(),b.diagnostics()));
        assertEquals(OrderChangeHistoryVerificationResult.Displayability.NONE,result.displayability());
        assertFalse(result.issues().isEmpty());
    }
    @Test void limitExceededNeverProducesUsableHistory() {
        var b=empty();var result=verifier.verify(new OrderChangeEvidenceBundle(Status.LIMIT_EXCEEDED,b.order(),Map.of(),b.coverage(),List.of()));
        assertEquals(OrderChangeHistoryVerificationResult.Displayability.NONE,result.displayability());
        assertTrue(result.verifiedChanges().isEmpty());
    }
    @Test void savedRetainedReceiptPassesComponentChecks() {
        var f=new Feature125EvidenceFixture(false);var index=new OrderChangeEvidenceIndex(f.bundle());
        assertDoesNotThrow(()->new Feature125OrderChangeVerifier(index,new OrderChangeSnapshotChainVerifier(index)).verify(f.sets.get(EvidenceSet.HISTORIES).getFirst()));
    }
    @Test void retainedSuccess() { confirmed(new Feature125EvidenceFixture(false)); }
    @Test void reauthorizedSuccessWithNullHistoryEvidence() { confirmed(new Feature125EvidenceFixture(true)); }
    @Test void sameAmountReplacementUsesCandidateLocalId() { confirmed(new Feature125EvidenceFixture(false,true)); }
    static void confirmed(Feature125EvidenceFixture f) {
        var result=new OrderChangeHistoryVerifier().verify(f.bundle());
        assertEquals(1,result.verifiedChanges().size(),result.toString());
        assertEquals(f.before,result.verifiedChanges().getFirst().before());
        assertEquals(f.after,result.verifiedChanges().getFirst().after());
    }
}
