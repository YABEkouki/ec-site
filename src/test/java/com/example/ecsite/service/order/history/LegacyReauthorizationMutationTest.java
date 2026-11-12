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

class LegacyReauthorizationMutationTest {
    record Mutation(String name,Consumer<LegacyReauthorizationFixture> apply){@Override public String toString(){return name;}}
    static Stream<Mutation> bad(){return Stream.of(
        new Mutation("operation not complete",f->f.legacy.edit(EvidenceSet.OPERATIONS,0,"state","AUTHORIZING")),
        new Mutation("execution version marks F125",f->f.legacy.edit(EvidenceSet.OPERATIONS,0,"execution_version",1)),
        new Mutation("wrong operation amount",f->f.legacy.edit(EvidenceSet.OPERATIONS,0,"after_amount",1)),
        new Mutation("wrong operation quantity",f->f.legacy.edit(EvidenceSet.OPERATION_ITEMS,0,"after_quantity",4)),
        new Mutation("missing operation items",f->f.legacy.sets.get(EvidenceSet.OPERATION_ITEMS).clear()),
        new Mutation("unconsumed reservation",f->f.legacy.edit(EvidenceSet.RESERVATIONS,0,"state","HELD")),
        new Mutation("reservation quantity",f->f.legacy.edit(EvidenceSet.RESERVATIONS,0,"quantity",2)),
        new Mutation("failed new authorization",f->f.legacy.edit(EvidenceSet.TRANSACTIONS,1,"status","FAILED")),
        new Mutation("failed old cancel",f->f.legacy.edit(EvidenceSet.TRANSACTIONS,2,"status","FAILED")),
        new Mutation("foreign transaction",f->f.legacy.edit(EvidenceSet.TRANSACTIONS,1,"order_change_order_id",9)),
        new Mutation("wrong new auth revision",f->f.legacy.edit(EvidenceSet.TRANSACTIONS,1,"order_content_revision",0)),
        new Mutation("wrong cancel revision",f->f.legacy.edit(EvidenceSet.TRANSACTIONS,2,"order_content_revision",1)),
        new Mutation("missing financial requests",f->f.legacy.sets.get(EvidenceSet.FINANCIAL_REQUESTS).clear()),
        new Mutation("request points to old authorization",f->f.legacy.edit(EvidenceSet.FINANCIAL_REQUESTS,0,"transaction_id",2000)),
        new Mutation("request body corrupt",f->{var r=f.legacy.sets.get(EvidenceSet.FINANCIAL_REQUESTS).getFirst();f.legacy.sets.get(EvidenceSet.FINANCIAL_REQUESTS).set(0,binary(r,"request_body",new byte[]{0}));}),
        new Mutation("dispatch not observed",f->f.legacy.edit(EvidenceSet.DISPATCHES,0,"state","STARTED")),
        new Mutation("missing dispatch",f->f.legacy.sets.get(EvidenceSet.DISPATCHES).clear()),
        new Mutation("recovery incomplete",f->f.recovery("COMMIT_PENDING")),
        new Mutation("recovery version",f->{f.recovery("COMPLETED");f.legacy.edit(EvidenceSet.RECOVERY_AUDITS,0,"completed_version",11);}),
        new Mutation("recovery history",f->{f.recovery("COMPLETED");f.legacy.edit(EvidenceSet.RECOVERY_AUDITS,0,"content_change_history_id",999);}),
        new Mutation("hold cannot carry application",f->{f.recovery("HOLD");f.legacy.edit(EvidenceSet.RECOVERY_AUDITS,0,"applied_version",9);}),
        new Mutation("duplicate applied recovery",f->{f.recovery("COMPLETED");f.legacy.add(EvidenceSet.RECOVERY_AUDITS,scalar(f.legacy.sets.get(EvidenceSet.RECOVERY_AUDITS).getFirst(),"request_id",UUID.randomUUID()));})
    );}
    @ParameterizedTest(name="{0}") @MethodSource("bad") void contradictionsNeverBecomeConfirmed(Mutation mutation){
        var f=new LegacyReauthorizationFixture();mutation.apply().accept(f);var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertTrue(r.verifiedChanges().isEmpty(),mutation.name());assertFalse(r.issues().isEmpty());
    }
    @Test void sameItemInAnotherPendingOperationDoesNotInvalidatePastCompletion(){
        var f=new LegacyReauthorizationFixture();UUID other=UUID.fromString("90000000-0000-0000-0000-000000000003");var op=f.legacy.sets.get(EvidenceSet.OPERATIONS).getFirst();
        op=scalar(scalar(scalar(scalar(op,"id",other),"submission_key",other),"content_change_history_id",null),"state","AUTHORIZING");f.legacy.add(EvidenceSet.OPERATIONS,op);f.legacy.add(EvidenceSet.OPERATION_ITEMS,scalar(f.legacy.sets.get(EvidenceSet.OPERATION_ITEMS).getFirst(),"operation_id",other));LegacyReauthorizationVerifierTest.confirmed(f);
    }
    @Test void normalRecoveryHoldOrConflictWithoutHistoryIsNotAnEmptyHistoryError(){for(String code:List.of("HOLD","CONFLICT")){
        var f=new LegacyReauthorizationFixture();f.recovery(code);f.legacy.sets.get(EvidenceSet.HISTORIES).clear();f.legacy.sets.get(EvidenceSet.ITEM_CHANGES).clear();f.legacy.edit(EvidenceSet.OPERATIONS,0,"content_change_history_id",null);f.legacy.edit(EvidenceSet.OPERATIONS,0,"state","MANUAL_REVIEW");
        var r=new OrderChangeHistoryVerifier().verify(f.bundle());assertTrue(r.verifiedChanges().isEmpty());assertTrue(r.issues().isEmpty(),r.toString());
    }}
    @Test void foreignFailureClosureAssociationIsNeverLegacy(){
        var f=new LegacyReauthorizationFixture();f.legacy.add(EvidenceSet.FAILURE_CONTROLS,row("id",UUID.randomUUID(),"order_id",1,"operation_id",LegacyReauthorizationFixture.OP));assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void recoveredDecisionMustBeInsideSavedAcceptanceWindow(){for(String decision:List.of("2026-01-01T07:59:59","2026-01-02T14:10:01")){
        var f=new LegacyReauthorizationFixture();f.recovery("COMPLETED");f.legacy.edit(EvidenceSet.OPERATIONS,0,"switch_decided_at",decision);assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }}
    @Test void oldPaymentCannotExceedSavedBeforeAmountEvenWithConsistentRequests(){
        var f=new LegacyReauthorizationFixture();f.recovery("COMPLETED");f.legacy.edit(EvidenceSet.PAYMENTS,0,"amount",6001);f.legacy.edit(EvidenceSet.TRANSACTIONS,0,"amount",6001);f.legacy.edit(EvidenceSet.TRANSACTIONS,2,"amount",6001);
        f.legacy.sets.get(EvidenceSet.FINANCIAL_REQUESTS).clear();f.legacy.sets.get(EvidenceSet.DISPATCHES).clear();var c=new com.example.ecsite.payment.capture.CaptureConnectionIdentity("saved-connection","saved-account","TEST","https://api.pay.jp");
        f.request(3000,1001,2001,com.example.ecsite.payment.orderchange.OrderChangeHttpRequest.create(c,LegacyReauthorizationFixture.OP,1,12000,"create"));f.request(3001,1000,2002,com.example.ecsite.payment.orderchange.OrderChangeHttpRequest.cancel(c,LegacyReauthorizationFixture.OP,1,6001,"cancel","pfw_legacy_old",false));assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
    @Test void recoveredOldAuthorizationRevisionMustMatchRecoveryBase(){
        var f=new LegacyReauthorizationFixture();f.recovery("COMPLETED");f.legacy.edit(EvidenceSet.OPERATIONS,0,"base_content_revision",1);f.legacy.edit(EvidenceSet.TRANSACTIONS,1,"order_content_revision",2);f.legacy.edit(EvidenceSet.TRANSACTIONS,2,"order_content_revision",1);
        var b=f.bundle();var updated=new OrderChangeEvidenceBundle(b.status(),new OrderEvidence(1,2,2,null,null),b.evidence(),b.coverage(),b.diagnostics());assertTrue(new OrderChangeHistoryVerifier().verify(updated).verifiedChanges().isEmpty());
    }
    @Test void completedOperationCannotHaveAFutureRevision(){
        var f=new LegacyReauthorizationFixture();f.legacy.edit(EvidenceSet.OPERATIONS,0,"base_content_revision",2);f.legacy.edit(EvidenceSet.TRANSACTIONS,1,"order_content_revision",3);f.legacy.edit(EvidenceSet.TRANSACTIONS,2,"order_content_revision",2);assertTrue(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().isEmpty());
    }
}
