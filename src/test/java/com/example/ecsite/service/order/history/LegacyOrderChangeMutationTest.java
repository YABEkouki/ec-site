package com.example.ecsite.service.order.history;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;
import static com.example.ecsite.service.order.history.Feature125EvidenceFixture.*;

class LegacyOrderChangeMutationTest {
    record Mutation(String name,Consumer<LegacyOrderChangeFixture> apply){@Override public String toString(){return name;}}
    static Stream<Mutation> bad(){return Stream.of(
        new Mutation("source",f->f.edit(EvidenceSet.HISTORIES,0,"change_source","ADMIN")),
        new Mutation("actor",f->f.edit(EvidenceSet.HISTORIES,0,"changed_by_type","SYSTEM")),
        new Mutation("owner",f->f.edit(EvidenceSet.HISTORIES,0,"changed_by_account_id",9)),
        new Mutation("reason",f->f.edit(EvidenceSet.HISTORIES,0,"reason","unknown")),
        new Mutation("empty details",f->f.sets.get(EvidenceSet.ITEM_CHANGES).clear()),
        new Mutation("added",f->f.edit(EvidenceSet.ITEM_CHANGES,0,"change_type","ADDED")),
        new Mutation("unknown type",f->f.edit(EvidenceSet.ITEM_CHANGES,0,"change_type","FUTURE")),
        new Mutation("increase",f->f.edit(EvidenceSet.ITEM_CHANGES,0,"new_quantity",3)),
        new Mutation("same quantity",f->f.edit(EvidenceSet.ITEM_CHANGES,0,"new_quantity",2)),
        new Mutation("different price",f->f.edit(EvidenceSet.ITEM_CHANGES,0,"new_price",7000)),
        new Mutation("wrong subtotal",f->f.edit(EvidenceSet.ITEM_CHANGES,0,"new_subtotal",1)),
        new Mutation("wrong parent",f->f.edit(EvidenceSet.HISTORIES,0,"new_item_subtotal",1)),
        new Mutation("wrong total",f->f.edit(EvidenceSet.HISTORIES,0,"new_total_amount",1)),
        new Mutation("negative residual",f->{f.edit(EvidenceSet.HISTORIES,0,"old_item_subtotal",1);f.edit(EvidenceSet.HISTORIES,0,"old_total_amount",1);}),
        new Mutation("duplicate item",f->f.add(EvidenceSet.ITEM_CHANGES,scalar(f.sets.get(EvidenceSet.ITEM_CHANGES).getFirst(),"id",999))),
        new Mutation("tax tuple mismatch",f->f.edit(EvidenceSet.ITEM_CHANGES,0,"new_tax_rate","8.00")),
        new Mutation("tax impossible for saved rates",f->f.edit(EvidenceSet.HISTORIES,0,"old_tax_amount",0)),
        new Mutation("negative tax",f->f.edit(EvidenceSet.HISTORIES,0,"new_tax_amount",-1)),
        new Mutation("overflow multiplication",f->f.edit(EvidenceSet.ITEM_CHANGES,0,"old_price",Integer.MAX_VALUE)),
        new Mutation("overflow parent",f->{f.edit(EvidenceSet.HISTORIES,0,"old_item_subtotal",Integer.MAX_VALUE);f.edit(EvidenceSet.HISTORIES,0,"old_charge_total",1);}),
        new Mutation("missing date",f->f.edit(EvidenceSet.HISTORIES,0,"changed_at",null)),
        new Mutation("unknown evidence",f->f.edit(EvidenceSet.HISTORIES,0,"evidence_version",2))
    );}
    @ParameterizedTest(name="{0}") @MethodSource("bad") void badLegacyNeverConfirms(Mutation mutation){
        var f=new LegacyOrderChangeFixture(false,false,false);mutation.apply().accept(f);var result=new OrderChangeHistoryVerifier().verify(f.bundle());assertTrue(result.verifiedChanges().isEmpty(),mutation.name());assertFalse(result.issues().isEmpty(),mutation.name());
    }
    @Test void chargeDifferenceMustMatchParent(){var f=new LegacyOrderChangeFixture(false,true,false);f.edit(EvidenceSet.CHARGE_CHANGES,0,"new_amount",551);assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());}
    @Test void feeDifferenceCannotDisappear(){var f=new LegacyOrderChangeFixture(false,true,false);f.sets.get(EvidenceSet.CHARGE_CHANGES).clear();assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());}
    @Test void deletionCannotInventZeroAfterValues(){var f=new LegacyOrderChangeFixture(true,true,false);f.edit(EvidenceSet.ITEM_CHANGES,0,"new_quantity",0);assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());}
    @Test void multipleChangedItemsUseParentDifferenceNotSumOfEntireOrder(){
        var f=new LegacyOrderChangeFixture(false,false,false);var item=f.sets.get(EvidenceSet.ITEM_CHANGES).getFirst();f.add(EvidenceSet.ITEM_CHANGES,scalar(scalar(item,"id",202),"order_item_id",11));
        f.edit(EvidenceSet.HISTORIES,0,"old_item_subtotal",24000);f.edit(EvidenceSet.HISTORIES,0,"old_total_amount",24000);f.edit(EvidenceSet.HISTORIES,0,"new_item_subtotal",12000);f.edit(EvidenceSet.HISTORIES,0,"new_total_amount",12000);f.edit(EvidenceSet.HISTORIES,0,"old_tax_amount",2181);f.edit(EvidenceSet.HISTORIES,0,"new_tax_amount",1090);LegacyOrderChangeVerifierTest.confirmed(f);
    }
    @Test void twoIndependentlyStoredHistoriesAtSameTimeSortById(){
        var f=new LegacyOrderChangeFixture(false,false,false);var h=f.sets.get(EvidenceSet.HISTORIES).getFirst();var i=f.sets.get(EvidenceSet.ITEM_CHANGES).getFirst();
        f.add(EvidenceSet.HISTORIES,scalar(h,"id",90));f.add(EvidenceSet.ITEM_CHANGES,scalar(scalar(i,"id",91),"history_id",90));
        var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertEquals(List.of(90L,100L),r.verifiedChanges().stream().map(VerifiedOrderChange::historyId).toList());
    }
    @Test void independentMalformedLegacyAllowsPartialDisplay(){
        var f=new LegacyOrderChangeFixture(false,false,false);var h=f.sets.get(EvidenceSet.HISTORIES).getFirst();f.add(EvidenceSet.HISTORIES,scalar(h,"id",90));
        var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertEquals(Displayability.PARTIAL,r.displayability());assertEquals(List.of(100L),r.verifiedChanges().stream().map(VerifiedOrderChange::historyId).toList());assertFalse(r.issues().isEmpty());
    }
    @Test void newAndOldHistoryAreBothConfirmedWithoutFallback(){
        var f=new Feature125EvidenceFixture(false);var legacy=new LegacyOrderChangeFixture(false,false,false);
        f.add(EvidenceSet.HISTORIES,scalar(legacy.sets.get(EvidenceSet.HISTORIES).getFirst(),"id",90));f.add(EvidenceSet.ITEM_CHANGES,scalar(scalar(legacy.sets.get(EvidenceSet.ITEM_CHANGES).getFirst(),"id",91),"history_id",90));
        var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertEquals(Displayability.ALL,r.displayability(),r.toString());assertEquals(List.of(90L,100L),r.verifiedChanges().stream().map(VerifiedOrderChange::historyId).toList());
        assertEquals(Classification.LEGACY_CONFIRMED,r.histories().getFirst().classification());assertEquals(Classification.FEATURE125_CONFIRMED,r.histories().get(1).classification());
    }
    @Test void brokenFeature125StillHidesAllOutputIncludingLegacy(){
        var f=new Feature125EvidenceFixture(false);var legacy=new LegacyOrderChangeFixture(false,false,false);f.add(EvidenceSet.HISTORIES,scalar(legacy.sets.get(EvidenceSet.HISTORIES).getFirst(),"id",90));f.add(EvidenceSet.ITEM_CHANGES,scalar(scalar(legacy.sets.get(EvidenceSet.ITEM_CHANGES).getFirst(),"id",91),"history_id",90));f.sets.get(EvidenceSet.ADMISSION_PROOFS).clear();
        var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertEquals(Displayability.NONE,r.displayability());assertTrue(r.verifiedChanges().isEmpty());
    }
}
