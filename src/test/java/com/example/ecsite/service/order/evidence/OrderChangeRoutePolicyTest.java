package com.example.ecsite.service.order.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidenceValidatorTest.*;
import static com.example.ecsite.service.order.evidence.OrderContentSnapshotCodecTest.snapshot;
import static org.assertj.core.api.Assertions.*;

class OrderChangeRoutePolicyTest {
    final OrderChangeRoutePolicy policy=new OrderChangeRoutePolicy();
    @ParameterizedTest @CsvSource({"6000,5000,RETAIN_AUTH","5000,6000,RETAIN_AUTH","6000,6000,RETAIN_AUTH","5000,5500,RETAIN_AUTH","6000,6500,REAUTHORIZE","5000,6500,REAUTHORIZE","5000,6001,REAUTHORIZE"})
    void comparesCandidateToAuthorizationRatherThanPreviousOrder(int before,int after,OrderChangeRoutePolicy.Route expected) {
        var e=initial();var changes=before==6000?List.<OrderPaymentEvidence.Change>of():List.of(change(e,null,31,before,7));
        var f=facts(e,changes);
        assertThat(policy.decide(f,snapshot(f.current().contentRevision(),after,8)).route()).isEqualTo(expected);
    }
    @Test void unchangedContentDoesNotCreateOperationAndInvalidEvidenceNeverPasses() {
        var f=facts(initial(),List.of());
        assertThat(policy.decide(f,f.current()).route()).isEqualTo(OrderChangeRoutePolicy.Route.NO_CHANGE);
        var broken=copy(f,f.current(),11L,f.payments(),f.authorizations(),f.epoch(),List.of(),null,false,false);
        assertThat(policy.decide(broken,f.current()).route()).isEqualTo(OrderChangeRoutePolicy.Route.BLOCKED);
    }
    @Test void staleRevisionAndForeignCandidateAreBlocked() {
        var f=facts(initial(),List.of());
        assertThat(policy.decide(f,snapshot(1,5000,8)).route()).isEqualTo(OrderChangeRoutePolicy.Route.BLOCKED);
        var s=snapshot(0,5000,8);
        var foreign=new OrderContentSnapshot(1,99,2,0,s.items(),s.charges(),s.itemSubtotal(),s.chargeTotal(),s.taxAmount(),s.totalAmount());
        assertThat(policy.decide(f,foreign).route()).isEqualTo(OrderChangeRoutePolicy.Route.BLOCKED);
    }
    @Test void splittingIdenticalOfferingsDoesNotCreateAChange() {
        var f=facts(initial(),List.of());var base=f.current();
        var a=new OrderContentSnapshot.Item(30,7,"商品",4,"分類",6000,1,6000,5,"STANDARD","標準",1000);
        var renumbered=new OrderContentSnapshot(1,1,2,0,List.of(a),base.charges(),6000,0,545,6000);
        assertThat(policy.decide(f,renumbered).route()).isEqualTo(OrderChangeRoutePolicy.Route.NO_CHANGE);
        var original=new OrderContentSnapshot.Item(3,7,"商品",4,"分類",3000,2,6000,5,"STANDARD","標準",1000);
        var one=new OrderContentSnapshot.Item(3,7,"商品",4,"分類",3000,1,3000,5,"STANDARD","標準",1000);
        var two=new OrderContentSnapshot.Item(9,7,"商品",4,"分類",3000,1,3000,5,"STANDARD","標準",1000);
        var start=new OrderContentSnapshot(1,1,2,0,List.of(original),base.charges(),6000,0,545,6000);
        var epoch=signed(new OrderPaymentEvidence.Epoch(1,EPOCH,1,10,20,6000,0,OrderPaymentEvidence.Origin.INITIAL_AUTH,start,null,null,ZERO));
        var split=new OrderContentSnapshot(1,1,2,0,List.of(two,one),base.charges(),6000,0,545,6000);
        assertThat(policy.decide(facts(epoch,List.of()),split).route()).isEqualTo(OrderChangeRoutePolicy.Route.NO_CHANGE);
    }
}
