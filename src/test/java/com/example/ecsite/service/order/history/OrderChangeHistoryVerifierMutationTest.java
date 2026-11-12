package com.example.ecsite.service.order.history;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;
import static com.example.ecsite.service.order.history.Feature125EvidenceFixture.*;
import static com.example.ecsite.service.order.history.OrderChangeHistoryVerificationResult.*;

class OrderChangeHistoryVerifierMutationTest {
    record Mutation(String name,Consumer<Feature125EvidenceFixture> apply){@Override public String toString(){return name;}}
    static Stream<Mutation> corruptions(){return Stream.of(
        new Mutation("generation scope mismatch",f->f.edit(EvidenceSet.GENERATIONS,0,"scope","OPERATIONAL")),
        new Mutation("operation shipping amount",f->f.edit(EvidenceSet.OPERATIONS,0,"shipping_amount",1)),
        new Mutation("control baseline broken",f->f.edit(EvidenceSet.CONTROLS,0,"baseline","{\"changed\":true}")),
        new Mutation("missing operation ownership column",f->{var r=f.sets.get(EvidenceSet.OPERATIONS).getFirst();var m=new LinkedHashMap<>(r.values());m.remove("order_id");f.sets.get(EvidenceSet.OPERATIONS).set(0,new EvidenceRow(m,r.binaries()));}),
        new Mutation("missing proof",f->f.sets.get(EvidenceSet.ADMISSION_PROOFS).clear()),
        new Mutation("event gap",f->f.sets.get(EvidenceSet.EVENTS).remove(2)),
        new Mutation("proof decision",f->f.edit(EvidenceSet.ADMISSION_PROOFS,0,"decision","REJECTED_LATE")),
        new Mutation("late original commit",f->f.edit(EvidenceSet.ADMISSION_PROOFS,0,"original_commit_at",DEADLINE)),
        new Mutation("unknown app version",f->f.edit(EvidenceSet.RESULTS,0,"application_version",2)),
        new Mutation("unknown evidence version",f->f.edit(EvidenceSet.HISTORIES,0,"evidence_version",2)),
        new Mutation("revision",f->f.edit(EvidenceSet.RESULTS,0,"application_after_revision",3)),
        new Mutation("foreign operation",f->f.edit(EvidenceSet.OPERATIONS,0,"order_id",9)),
        new Mutation("foreign customer",f->f.edit(EvidenceSet.ADMISSIONS,0,"customer_id",9)),
        new Mutation("duplicate ID",f->f.add(EvidenceSet.HISTORIES,f.sets.get(EvidenceSet.HISTORIES).getFirst())),
        new Mutation("duplicate event key",f->f.add(EvidenceSet.EVENTS,f.sets.get(EvidenceSet.EVENTS).getFirst())),
        new Mutation("duplicate product diff",f->{var d=f.sets.get(EvidenceSet.ITEM_CHANGES).getFirst();f.add(EvidenceSet.ITEM_CHANGES,scalar(d,"id",2));}),
        new Mutation("APPLIED history absent",f->f.sets.get(EvidenceSet.HISTORIES).clear()),
        new Mutation("wrong result attempt",f->f.edit(EvidenceSet.RESULTS,0,"attempt_id",OP)),
        new Mutation("control fence",f->f.edit(EvidenceSet.CONTROLS,0,"fencing_token",3)),
        new Mutation("attempt generation",f->f.edit(EvidenceSet.ATTEMPTS,0,"db_generation_id",OP)),
        new Mutation("result reason",f->f.edit(EvidenceSet.RESULTS,0,"reason_code","OTHER")),
        new Mutation("recovery APPLIED",f->f.edit(EvidenceSet.RESULTS,0,"recovery",true)),
        new Mutation("operation not completed",f->f.edit(EvidenceSet.OPERATIONS,0,"state","AUTHORIZING")),
        new Mutation("admission not completed",f->f.edit(EvidenceSet.ADMISSIONS,0,"execution_state","OBSERVING")),
        new Mutation("old parent amount",f->f.edit(EvidenceSet.HISTORIES,0,"old_item_subtotal",1)),
        new Mutation("diff subtotal",f->f.edit(EvidenceSet.ITEM_CHANGES,0,"new_subtotal",1)),
        new Mutation("NULL tax evidence",f->f.edit(EvidenceSet.ITEM_CHANGES,0,"new_tax_category_id",null)),
        new Mutation("missing column",f->{var r=f.sets.get(EvidenceSet.HISTORIES).getFirst();var m=new LinkedHashMap<>(r.values());m.remove("reason");f.sets.get(EvidenceSet.HISTORIES).set(0,new EvidenceRow(m,r.binaries()));}),
        new Mutation("unapplied contradiction",f->f.add(EvidenceSet.UNAPPLIED,row("admission_id",AD,"order_id",1))),
        new Mutation("failure closure contradiction",f->f.add(EvidenceSet.FAILURE_BUSINESS_CLOSURES,row("control_id",OP,"admission_id",AD,"order_id",1))),
        new Mutation("raw snapshot broken",f->{var r=f.sets.get(EvidenceSet.HISTORIES).getFirst();f.sets.get(EvidenceSet.HISTORIES).set(0,binary(r,"after_snapshot",new byte[]{0}));}),
        new Mutation("fingerprint",f->{var r=f.sets.get(EvidenceSet.RESULTS).getFirst();f.sets.get(EvidenceSet.RESULTS).set(0,binary(r,"application_fingerprint",new byte[32]));}),
        new Mutation("change digest",f->{var r=f.sets.get(EvidenceSet.HISTORIES).getFirst();f.sets.get(EvidenceSet.HISTORIES).set(0,binary(r,"evidence_digest",new byte[32]));}),
        new Mutation("financial binding",f->{var r=f.sets.get(EvidenceSet.ADMISSION_PROOFS).getFirst();f.sets.get(EvidenceSet.ADMISSION_PROOFS).set(0,binary(r,"financial_binding_digest",new byte[32]));})
    );}
    @ParameterizedTest(name="{0}") @MethodSource("corruptions") void corruptionNeverFallsBackToLegacy(Mutation mutation){
        var f=new Feature125EvidenceFixture(false);mutation.apply().accept(f);var result=new OrderChangeHistoryVerifier().verify(f.bundle());
        assertTrue(result.verifiedChanges().isEmpty(),mutation.name());assertEquals(Displayability.NONE,result.displayability());
        assertTrue(result.histories().stream().allMatch(d->d.classification()==Classification.UNVERIFIED));assertFalse(result.issues().isEmpty(),mutation.name());
    }
    @Test void incompleteLegacyCandidateRemainsUnverified(){
        var b=OrderChangeHistoryVerifierTest.empty();var sets=new EnumMap<>(b.evidence());
        sets.put(EvidenceSet.HISTORIES,List.of(row("id",1,"order_id",1,"previous_history_id",null,"evidence_version",null)));
        var r=new OrderChangeHistoryVerifier().verify(new OrderChangeEvidenceBundle(b.status(),b.order(),sets,b.coverage(),List.of()));
        assertEquals(Classification.UNVERIFIED,r.histories().getFirst().classification());assertTrue(r.verifiedChanges().isEmpty());
    }
    @Test void currentPaymentLifecycleDoesNotInvalidateHistory(){for(String state:List.of("AUTHORIZED","CAPTURED","CANCELLED")){
        var f=new Feature125EvidenceFixture(false);f.edit(EvidenceSet.PAYMENTS,0,"status",state);OrderChangeHistoryVerifierTest.confirmed(f);
    }}
    @Test void incompleteCollectionIsRejectedEvenWhenOtherEvidenceIsValid(){
        var f=new Feature125EvidenceFixture(false);f.sets.remove(EvidenceSet.RESULTS);var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertEquals(Displayability.NONE,r.displayability());assertTrue(r.verifiedChanges().isEmpty());
    }
    @Test void normalPendingAndFailedAdmissionsWithoutHistoryDoNotWarn(){for(String state:List.of("OBSERVING","FAILED_UNAPPLIED","CLOSED_UNAPPLIED")){
        var f=new Feature125EvidenceFixture(false);
        f.sets.get(EvidenceSet.HISTORIES).clear();f.sets.get(EvidenceSet.ITEM_CHANGES).clear();f.sets.get(EvidenceSet.OPERATIONS).clear();f.sets.get(EvidenceSet.RESULTS).clear();
        f.edit(EvidenceSet.ADMISSIONS,0,"execution_state",state);f.edit(EvidenceSet.ADMISSIONS,0,"applied_operation_id",null);
        var b=f.bundle();var r=new OrderChangeHistoryVerifier().verify(new OrderChangeEvidenceBundle(b.status(),new OrderEvidence(1,2,0,EPOCH.toString(),null),b.evidence(),b.coverage(),b.diagnostics()));
        assertTrue(r.issues().isEmpty(),r.toString());assertTrue(r.verifiedChanges().isEmpty());
    }}
    @Test void onlyPureDependencies(){
        for(var type:List.of(OrderChangeHistoryVerifier.class,OrderChangeEvidenceIndex.class,Feature125OrderChangeVerifier.class,OrderChangeSnapshotChainVerifier.class,OrderChangeHistoryDifferenceVerifier.class,OrderChangeFinancialEvidenceVerifier.class,com.example.ecsite.service.order.evidence.HistoricalOrderCheckpointValidation.class))
            for(var field:type.getDeclaredFields())assertFalse(field.getType().getName().matches(".*(DataSource|Repository|Gateway|SecurityContext|Clock|RestClient|JdbcTemplate).*"),field.toString());
    }
    @Test void noContentChangeIsRejected(){
        var f=new Feature125EvidenceFixture(false,false,true);assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void corruptedApplicationObservationIsRejected(){
        var f=new Feature125EvidenceFixture(false);var op=f.sets.get(EvidenceSet.OPERATIONS).getFirst();f.sets.get(EvidenceSet.OPERATIONS).set(0,binary(op,"admission_observation_fingerprint",new byte[32]));
        assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void wrongNewAuthorizationOrderIsRejected(){
        var f=new Feature125EvidenceFixture(true);f.edit(EvidenceSet.TRANSACTIONS,1,"order_change_order_id",9);assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void missingReauthorizationFinancialRequestIsRejected(){
        var f=new Feature125EvidenceFixture(true);f.sets.get(EvidenceSet.FINANCIAL_REQUESTS).clear();assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void unrelatedReauthorizationObservationIsRejectedEvenWithMatchingHash(){
        var f=new Feature125EvidenceFixture(true);var e=f.sets.get(EvidenceSet.EPOCHS).get(1);var raw=observation("pfw_unrelated",f.after.totalAmount(),OP);
        f.sets.get(EvidenceSet.EPOCHS).set(1,binary(e,"observation_payload",raw,"observation_fingerprint",OrderChangeEvidenceChecks.hash(raw)));
        assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void multipleResolvedAttemptsCanPrecedeApplication(){
        var f=new Feature125EvidenceFixture(false);var previous=UUID.fromString("50000000-0000-0000-0000-000000000002");
        var attempt=f.sets.get(EvidenceSet.ATTEMPTS).getFirst();f.add(EvidenceSet.ATTEMPTS,scalar(attempt,"id",previous));
        f.edit(EvidenceSet.ATTEMPTS,0,"control_version",3);f.edit(EvidenceSet.ATTEMPTS,0,"fencing_token",3);
        var applied=f.sets.get(EvidenceSet.RESULTS).getFirst();var requeue=scalar(scalar(scalar(applied,"id",UUID.fromString("60000000-0000-0000-0000-000000000002")),"attempt_id",previous),"disposition","REQUEUE");
        for(var k:List.of("application_version","application_operation_id","application_history_id","application_before_revision","application_after_revision"))requeue=scalar(requeue,k,null);
        requeue=binary(requeue,"application_fingerprint",null);f.add(EvidenceSet.RESULTS,requeue);
        f.edit(EvidenceSet.RESULTS,0,"control_version",3);f.edit(EvidenceSet.RESULTS,0,"fencing_token",3);
        f.edit(EvidenceSet.CONTROLS,0,"get_attempt_count",2);f.edit(EvidenceSet.CONTROLS,0,"version",4);f.edit(EvidenceSet.CONTROLS,0,"fencing_token",4);
        OrderChangeHistoryVerifierTest.confirmed(f);
    }
    @Test void contradictoryEpochLinkIsRejected(){
        var f=new Feature125EvidenceFixture(true);f.edit(EvidenceSet.EPOCHS,1,"previous_epoch_id",NEXT);assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void rootOwnershipMismatchHidesEverything(){
        var f=new Feature125EvidenceFixture(false);var b=f.bundle();var r=new OrderChangeHistoryVerifier().verify(new OrderChangeEvidenceBundle(b.status(),new OrderEvidence(1,9,1,EPOCH.toString(),100L),b.evidence(),b.coverage(),List.of()));assertEquals(Displayability.NONE,r.displayability());
    }
    @Test void checkpointBaseIsSupported(){OrderChangeHistoryVerifierTest.confirmed(new Feature125EvidenceFixture(false,false,false,true,false));}
    @Test void checkpointLocalAssessmentMustMatchEpoch(){var f=new Feature125EvidenceFixture(false,false,false,true,true);assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());}
    @Test void legacyAndFeature125RemainExclusive(){
        var f=new Feature125EvidenceFixture(false);f.add(EvidenceSet.HISTORIES,row("id",90,"order_id",1,"evidence_version",null,"previous_history_id",null));
        var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertEquals(Displayability.PARTIAL,r.displayability());assertEquals(1,r.verifiedChanges().size());assertEquals(0,r.histories().stream().filter(d->d.classification()==Classification.LEGACY_CONFIRMED).count());
    }
    @Test void appliedWithoutHistoryHasExplicitIssue(){
        var f=new Feature125EvidenceFixture(false);f.sets.get(EvidenceSet.HISTORIES).clear();var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertTrue(r.issues().stream().anyMatch(i->i.reason()==Reason.APPLIED_WITHOUT_HISTORY));
    }
    @Test void failureControlCannotClaimCompletedOperationThroughAnotherAdmission(){
        var f=new Feature125EvidenceFixture(false);f.add(EvidenceSet.FAILURE_CONTROLS,row("id",UUID.randomUUID(),"order_id",1,"admission_id",NEXT,"operation_id",OP));assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void malformedObservationIsARejectionNotAnException(){
        var f=new Feature125EvidenceFixture(false);var op=f.sets.get(EvidenceSet.OPERATIONS).getFirst();byte[] raw=new byte[]{123};f.sets.get(EvidenceSet.OPERATIONS).set(0,binary(op,"admission_observation_payload",raw,"admission_observation_fingerprint",OrderChangeEvidenceChecks.hash(raw)));
        assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void malformedObservationTimeIsARejectionNotAnException(){
        var f=new Feature125EvidenceFixture(false);var op=f.sets.get(EvidenceSet.OPERATIONS).getFirst();byte[] raw=new String(observation("pfw_old",11000,null),java.nio.charset.StandardCharsets.UTF_8).replace(TIME,"not-a-time").getBytes(java.nio.charset.StandardCharsets.UTF_8);f.sets.get(EvidenceSet.OPERATIONS).set(0,binary(op,"admission_observation_payload",raw,"admission_observation_fingerprint",OrderChangeEvidenceChecks.hash(raw)));
        assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void rootCannotForgetItsRetainedHead(){
        var f=new Feature125EvidenceFixture(false);var b=f.bundle();var r=new OrderChangeHistoryVerifier().verify(new OrderChangeEvidenceBundle(b.status(),new OrderEvidence(1,2,1,EPOCH.toString(),null),b.evidence(),b.coverage(),List.of()));assertTrue(r.verifiedChanges().isEmpty());
    }
    @Test void equivalentTimezoneOffsetsPreserveHistory(){
        var f=new Feature125EvidenceFixture(false);f.edit(EvidenceSet.ADMISSION_PROOFS,0,"deadline_at","2026-01-02T09:00:00+09:00");f.edit(EvidenceSet.EVENTS,4,"recorded_at","2026-01-01T09:00:00+09:00");OrderChangeHistoryVerifierTest.confirmed(f);
    }
    @Test void oldAndNewPaymentsCannotReuseProviderFlow(){
        var f=new Feature125EvidenceFixture(true);f.edit(EvidenceSet.PAYMENTS,1,"provider_payment_id","pfw_old");f.edit(EvidenceSet.TRANSACTIONS,1,"provider_transaction_id","pfw_old");f.edit(EvidenceSet.EPOCHS,1,"provider_flow_id","pfw_old");
        var e=f.sets.get(EvidenceSet.EPOCHS).get(1);var raw=observation("pfw_old",f.after.totalAmount(),OP);f.sets.get(EvidenceSet.EPOCHS).set(1,binary(e,"observation_payload",raw,"observation_fingerprint",OrderChangeEvidenceChecks.hash(raw)));
        assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void admissionCannotInventAnotherAuthorizationAmountThanBaseEpoch(){
        var f=new Feature125EvidenceFixture(false,false,false,false,false,11001);assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void operationMustCarryCustomerOwnershipColumn(){
        var f=new Feature125EvidenceFixture(false);var op=f.sets.get(EvidenceSet.OPERATIONS).getFirst();var m=new LinkedHashMap<>(op.values());m.remove("customer_id");f.sets.get(EvidenceSet.OPERATIONS).set(0,new EvidenceRow(m,op.binaries()));assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void historyIdentityMustBePositive(){
        var b=OrderChangeHistoryVerifierTest.empty();var sets=new EnumMap<>(b.evidence());sets.put(EvidenceSet.HISTORIES,List.of(row("id",-1,"order_id",1)));
        assertThrows(OrderChangeEvidenceChecks.Invalid.class,()->new OrderChangeEvidenceIndex(new OrderChangeEvidenceBundle(b.status(),b.order(),sets,b.coverage(),List.of())));
    }
}
