package com.example.ecsite.service.order.history;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;

class LegacyOrderChangeVerifierTest {
    @Test void decreaseWithoutShippingChange(){confirmed(new LegacyOrderChangeFixture(false,false,false));}
    @Test void decreaseWithShippingChange(){confirmed(new LegacyOrderChangeFixture(false,true,false));}
    @Test void removalRetainsNullAfterSide(){
        var result=confirmed(new LegacyOrderChangeFixture(true,true,false));assertNull(result.legacy().items().getFirst().after());
    }
    @Test void missingTaxEvidenceIsPreservedNotRecomputed(){
        var result=confirmed(new LegacyOrderChangeFixture(false,true,true));assertNull(result.legacy().items().getFirst().before().tax().rate());assertEquals(545,result.legacy().before().taxAmount());
    }
    static VerifiedOrderChange confirmed(LegacyOrderChangeFixture f){
        var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertEquals(Displayability.ALL,r.displayability(),r.toString());assertEquals(Classification.LEGACY_CONFIRMED,r.histories().getFirst().classification());
        var change=r.verifiedChanges().getFirst();assertEquals(LocalDateTime.parse("2026-01-01T09:00:00"),change.legacyChangedAt());assertNull(change.changedAt());assertNull(change.before());assertNull(change.after());return change;
    }
}
