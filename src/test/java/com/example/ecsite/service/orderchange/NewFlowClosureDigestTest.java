package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.orderchange.NewFlowTerminationEvidence;
import com.example.ecsite.payment.payjp.NewFlowClosureObservation;

class NewFlowClosureDigestTest {
    NewFlowClosureControl.Expected expected(long version,String dispatch){
        var op=UUID.fromString("12345678-1234-1234-1234-123456789abc");
        return new NewFlowClosureControl.Expected(8,version,new NewFlowTerminationEvidence.Expected(1,op,"pfw_new",910,
            new CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp")),
            new NewFlowClosureObservation.AbortIntent(op,LocalDateTime.of(2026,10,23,9,0),"CUSTOMER","CUSTOMER_REQUEST",3),
            new NewFlowClosureObservation.RequestBinding(9,"00".repeat(32)),Optional.empty(),
            new NewFlowClosureObservation.FinancialSnapshot("payment",List.of("authorization"),dispatch));
    }
    @Test void fixedVersionOneKnownVector(){
        assertThat(HexFormat.of().formatHex(NewFlowClosureDigest.compute(expected(4,"READY"))))
            .isEqualTo("7b93ef6f55fd310a298d770126f6b9145f849711c1fa1cf907c5ed4a79734da6");
    }
    @Test void versionAndDispatchAreNotFinancialFact(){
        assertThat(NewFlowClosureDigest.compute(expected(4,"READY"))).hasSize(32)
            .isEqualTo(NewFlowClosureDigest.compute(expected(9,"OBSERVED")));
    }
    @Test void absentNullEmptyAndFieldBoundariesAreDistinct(){
        assertThat(NewFlowClosureDigest.hash(Arrays.asList("a",null))).isNotEqualTo(NewFlowClosureDigest.hash(List.of("a","")));
        assertThat(NewFlowClosureDigest.hash(List.of("ab","c"))).isNotEqualTo(NewFlowClosureDigest.hash(List.of("a","bc")));
    }
    @Test void optionalRequestPresenceAndFingerprintChangeDigest(){
        var e=expected(4,"READY");var present=new NewFlowClosureControl.Expected(e.paymentId(),e.version(),e.flow(),e.intent(),e.create(),
            Optional.of(new NewFlowClosureObservation.RequestBinding(10,"01".repeat(32))),e.snapshot());
        assertThat(NewFlowClosureDigest.compute(present)).isNotEqualTo(NewFlowClosureDigest.compute(e));
    }
}
