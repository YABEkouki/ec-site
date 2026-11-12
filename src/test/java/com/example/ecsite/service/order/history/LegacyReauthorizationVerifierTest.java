package com.example.ecsite.service.order.history;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;

class LegacyReauthorizationVerifierTest {
    @Test void successfulOldReauthorizationDoesNotNeedFeature125Result(){confirmed(new LegacyReauthorizationFixture());}
    @Test void completedRecoveryUsesSavedAuditWithoutReconstructingObservation(){var f=new LegacyReauthorizationFixture();f.recovery("COMPLETED");confirmed(f);}
    @Test void priorHoldOrConflictDoesNotOverrideIndependentCompletion(){for(String code:new String[]{"HOLD","CONFLICT"}){var f=new LegacyReauthorizationFixture();f.recovery(code);confirmed(f);}}
    @Test void appliedRecoveryWithoutCompletedAuditIsRejected(){var f=new LegacyReauthorizationFixture();f.recovery("COMMIT_PENDING");assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());}
    @Test void currentPaymentStatusesAreNotHistoricalEligibility(){var f=new LegacyReauthorizationFixture();f.legacy.edit(EvidenceSet.PAYMENTS,0,"status","CANCELLED");f.legacy.edit(EvidenceSet.PAYMENTS,1,"status","CAPTURED");confirmed(f);}
    static void confirmed(LegacyReauthorizationFixture f){var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertEquals(Displayability.ALL,r.displayability(),r.toString());assertEquals(Classification.LEGACY_CONFIRMED,r.histories().getFirst().classification());assertEquals(LegacyOrderChangeEvidence.Kind.LEGACY_REAUTHORIZE,r.verifiedChanges().getFirst().legacy().kind());}
}
