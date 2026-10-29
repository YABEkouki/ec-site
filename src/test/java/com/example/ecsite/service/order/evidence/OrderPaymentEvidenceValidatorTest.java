package com.example.ecsite.service.order.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static com.example.ecsite.service.order.evidence.OrderPaymentEvidence.*;
import static com.example.ecsite.service.order.evidence.OrderContentSnapshotCodecTest.snapshot;
import static org.assertj.core.api.Assertions.*;

class OrderPaymentEvidenceValidatorTest {
    static final UUID EPOCH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OP = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final String ZERO = "00".repeat(32);
    static final OrderContentSnapshotCodec CODEC = new OrderContentSnapshotCodec();
    final OrderPaymentEvidenceValidator validator = new OrderPaymentEvidenceValidator();

    static Epoch signed(Epoch e) { return new Epoch(e.version(), e.id(), e.orderId(), e.paymentId(), e.authorizationId(), e.authorizedAmount(), e.authorizationRevision(), e.origin(), e.start(), e.reauthorization(), e.checkpoint(), OrderEvidenceDigests.epoch(e)); }
    static Change signed(Change c) { return new Change(c.version(), c.id(), c.orderId(), c.operationId(), c.epochId(), c.previousHistoryId(), c.beforeRevision(), c.beforeAmount(), c.beforeFingerprint(), c.after(), c.previousDigest(), OrderEvidenceDigests.change(c)); }
    static Epoch initial() { return signed(new Epoch(1, EPOCH, 1, 10, 20, 6000, 0, Origin.INITIAL_AUTH, snapshot(0,6000,7), null, null, ZERO)); }
    static Facts facts(Epoch e, List<Change> changes) {
        var current = changes.isEmpty() ? e.start() : changes.getLast().after();
        return new Facts(current, 10L,
                List.of(new Payment(10,1,"PAYJP","CARD","AUTHORIZED",6000,"pfw_test")),
                List.of(new Authorization(20,1,10,"AUTHORIZE","SUCCEEDED",6000,0,"pfw_test","INITIAL_ORDER_AUTHORIZATION",null)),
                true,e,changes,changes.isEmpty()?null:changes.getLast().id(),false,false);
    }
    static Change change(Epoch e, Change before, long id, int amount, long product) {
        var base = before == null ? e.start() : before.after();
        return signed(new Change(1,id,1,new UUID(0,id),e.id(),before==null?null:before.id(),base.contentRevision(),base.totalAmount(),CODEC.fingerprint(base),snapshot(base.contentRevision()+1,amount,product),before==null?e.digest():before.digest(),ZERO));
    }
    static Facts copy(Facts f, OrderContentSnapshot current, Long active, List<Payment> payments, List<Authorization> auths, Epoch epoch, List<Change> changes, Long head, boolean unresolved, boolean pending) {
        return new Facts(current,active,payments,auths,f.evidenceRequired(),epoch,changes,head,unresolved,pending);
    }
    @Test void initialEvidenceIsLocalOnly() {
        var result = validator.validate(facts(initial(),List.of()));
        assertThat(result.disposition()).isEqualTo(OrderPaymentEvidenceDecision.Disposition.EVIDENCE_CURRENT);
        assertThat(result.requiresExternalVerification()).isTrue();
        assertThat(result.paymentId()).isEqualTo(10);
        assertThat(result.authorizationId()).isEqualTo(20);
    }
    @Test void multipleChangesCanReturnToOriginalAuthorizationAndReplaceProducts() {
        var e=initial(); var a=change(e,null,31,5000,7); var b=change(e,a,32,6000,8); var c=change(e,b,33,6000,9);
        var f=facts(e,List.of(a,b,c));
        assertThat(validator.validate(copy(f,f.current(),10L,f.payments(),f.authorizations(),e,List.of(c,a,b),c.id(),false,false)).disposition())
                .isEqualTo(OrderPaymentEvidenceDecision.Disposition.EVIDENCE_RETAINED_AUTH);
    }
    @Test void validatesCompletedReauthorizationOriginRatherThanAcceptingFlagAlone() {
        var start=snapshot(1,6000,7);
        var receipt=new Reauthorization(OP,1,9,10,20,29,0,6000,"COMPLETED",19,"SUCCEEDED",5000,0,"pfw_old");
        var e=signed(new Epoch(1,EPOCH,1,10,20,6000,1,Origin.REAUTH_COMPLETED,start,receipt,null,ZERO));
        var f=facts(e,List.of());
        var payments=new ArrayList<>(f.payments());payments.add(new Payment(9,1,"PAYJP","CARD","CANCELLED",5000,"pfw_old"));
        var auth=List.of(new Authorization(20,1,10,"AUTHORIZE","SUCCEEDED",6000,1,"pfw_test","ORDER_CHANGE_AUTHORIZATION",OP));
        assertThat(validator.validate(copy(f,start,10L,payments,auth,e,List.of(),null,false,false)).disposition()).isEqualTo(OrderPaymentEvidenceDecision.Disposition.EVIDENCE_CURRENT);
        var bad=signed(new Epoch(1,EPOCH,1,10,20,6000,1,Origin.REAUTH_COMPLETED,start,null,null,ZERO));
        assertThat(validator.validate(copy(f,start,10L,payments,auth,bad,List.of(),null,false,false)).disposition()).isEqualTo(OrderPaymentEvidenceDecision.Disposition.REVIEW_REQUIRED);
    }
    @Test void checkpointReceiptsDoNotProvePastHistoryOrExternalAuthorization() {
        var start=snapshot(2,5000,7);
        var checkpoint=new Checkpoint(1,10,20,2,CODEC.fingerprint(start),"11".repeat(32),"22".repeat(32));
        var e=signed(new Epoch(1,EPOCH,1,10,20,6000,0,Origin.LEGACY_CURRENT_CHECKPOINT,start,null,checkpoint,ZERO));
        var result=validator.validate(facts(e,List.of()));
        assertThat(result.disposition()).isEqualTo(OrderPaymentEvidenceDecision.Disposition.EVIDENCE_CURRENT);
        assertThat(result.requiresExternalVerification()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"missing","duplicate","branch","gap","digest","version","changeVersion","changeDigest","payment","current","head","predecessor","operation","owner","over"})
    void rejectsBrokenEvidenceWithoutDecreasedAmountFallback(String mutation) {
        var e=initial(); var a=change(e,null,31,5000,7); var b=change(e,a,32,5500,8); var f=facts(e,List.of(a,b));
        List<Change> changes=f.histories(); var current=f.current(); Long head=f.headHistoryId(); Epoch epoch=e;
        switch(mutation) {
            case "missing" -> changes=List.of(b);
            case "duplicate" -> changes=List.of(a,a,b);
            case "branch" -> changes=List.of(a,b,change(e,a,34,5500,9));
            case "gap" -> changes=List.of(a,signed(new Change(1,32,1,b.operationId(),EPOCH,31L,2,5000,CODEC.fingerprint(a.after()),snapshot(3,5500,8),a.digest(),ZERO)));
            case "digest" -> epoch=new Epoch(1,EPOCH,1,10,20,6000,0,e.origin(),e.start(),null,null,ZERO);
            case "version" -> epoch=signed(new Epoch(2,EPOCH,1,10,20,6000,0,e.origin(),e.start(),null,null,ZERO));
            case "changeVersion" -> changes=List.of(a,signed(new Change(2,32,1,b.operationId(),EPOCH,31L,1,5000,CODEC.fingerprint(a.after()),b.after(),a.digest(),ZERO)));
            case "changeDigest" -> changes=List.of(a,new Change(1,32,1,b.operationId(),EPOCH,31L,1,5000,CODEC.fingerprint(a.after()),b.after(),a.digest(),ZERO));
            case "payment" -> epoch=signed(new Epoch(1,EPOCH,1,11,20,6000,0,e.origin(),e.start(),null,null,ZERO));
            case "current" -> current=snapshot(2,5500,99);
            case "head" -> head=31L;
            case "predecessor" -> changes=List.of(a,signed(new Change(1,32,1,b.operationId(),EPOCH,31L,1,5000,CODEC.fingerprint(a.after()),b.after(),ZERO,ZERO)));
            case "operation" -> changes=List.of(a,signed(new Change(1,32,1,a.operationId(),EPOCH,31L,1,5000,CODEC.fingerprint(a.after()),b.after(),a.digest(),ZERO)));
            case "owner" -> epoch=signed(new Epoch(1,EPOCH,99,10,20,6000,0,e.origin(),e.start(),null,null,ZERO));
            default -> { var over=change(e,b,33,6001,9);changes=List.of(a,b,over);current=over.after();head=33L; }
        }
        assertThat(validator.validate(copy(f,current,10L,f.payments(),f.authorizations(),epoch,changes,head,false,false)).disposition())
                .isIn(OrderPaymentEvidenceDecision.Disposition.BLOCKED,OrderPaymentEvidenceDecision.Disposition.REVIEW_REQUIRED);
    }
    @ParameterizedTest @ValueSource(strings={"active","ambiguous","authorization","amount","flow","pending","unresolved","failed","provider","purpose","revision"})
    void blocksInvalidFinancialFacts(String mutation) {
        var f=facts(initial(),List.of());Long active=10L;var ps=f.payments();var as=f.authorizations();boolean pending=false, unresolved=false;
        switch(mutation) {
            case "active" -> active=11L;
            case "ambiguous" -> ps=List.of(ps.getFirst(),new Payment(11,1,"PAYJP","CARD","AUTHORIZED",6000,"pfw_other"));
            case "authorization" -> as=List.of(as.getFirst(),as.getFirst());
            case "amount" -> as=List.of(new Authorization(20,1,10,"AUTHORIZE","SUCCEEDED",5999,0,"pfw_test","INITIAL_ORDER_AUTHORIZATION",null));
            case "flow" -> as=List.of(new Authorization(20,1,10,"AUTHORIZE","SUCCEEDED",6000,0,"pfw_wrong","INITIAL_ORDER_AUTHORIZATION",null));
            case "pending" -> pending=true;
            case "unresolved" -> unresolved=true;
            case "failed" -> ps=List.of(new Payment(10,1,"PAYJP","CARD","FAILED",6000,"pfw_test"));
            case "provider" -> ps=List.of(new Payment(10,1,"OTHER","CARD","AUTHORIZED",6000,"pfw_test"));
            case "purpose" -> as=List.of(new Authorization(20,1,10,"AUTHORIZE","SUCCEEDED",6000,0,"pfw_test","LEGACY_UNKNOWN",null));
            default -> as=List.of(new Authorization(20,1,10,"AUTHORIZE","SUCCEEDED",6000,1,"pfw_test","INITIAL_ORDER_AUTHORIZATION",null));
        }
        assertThat(validator.validate(copy(f,f.current(),active,ps,as,f.epoch(),List.of(),null,unresolved,pending)).disposition())
                .isIn(OrderPaymentEvidenceDecision.Disposition.BLOCKED,OrderPaymentEvidenceDecision.Disposition.REVIEW_REQUIRED);
    }
    @Test void missingEvidenceAndLegacyAreDifferentAndNeitherGrantsPermission() {
        var f=facts(initial(),List.of());
        assertThat(validator.validate(copy(f,f.current(),10L,f.payments(),f.authorizations(),null,List.of(),null,false,false)).disposition())
                .isEqualTo(OrderPaymentEvidenceDecision.Disposition.REVIEW_REQUIRED);
        var legacy=new Facts(f.current(),10L,f.payments(),f.authorizations(),false,null,List.of(),null,false,false);
        assertThat(validator.validate(legacy).disposition()).isEqualTo(OrderPaymentEvidenceDecision.Disposition.LEGACY_UNENROLLED);
    }
    @Test void aRevisionIncrementWithNoBusinessChangeIsNotValidChangeEvidence() {
        var e=initial();var noop=change(e,null,31,6000,7);
        assertThat(validator.validate(facts(e,List.of(noop))).disposition()).isEqualTo(OrderPaymentEvidenceDecision.Disposition.REVIEW_REQUIRED);
    }
    @Test void cancellationCannotReuseAnAuthorizationTransactionIdentity() {
        var receipt=new Reauthorization(OP,1,9,10,20,29,0,6000,"COMPLETED",19,"SUCCEEDED",5000,0,"pfw_old");
        var e=signed(new Epoch(1,EPOCH,1,10,20,6000,1,Origin.REAUTH_COMPLETED,snapshot(1,6000,7),receipt,null,ZERO));
        var f=facts(e,List.of());
        var ps=List.of(f.payments().getFirst(),new Payment(9,1,"PAYJP","CARD","CANCELLED",5000,"pfw_old"));
        var as=List.of(new Authorization(20,1,10,"AUTHORIZE","SUCCEEDED",6000,1,"pfw_test","ORDER_CHANGE_AUTHORIZATION",OP),
                new Authorization(19,1,9,"AUTHORIZE","SUCCEEDED",5000,0,"pfw_old","INITIAL_ORDER_AUTHORIZATION",null));
        assertThat(validator.validate(copy(f,e.start(),10L,ps,as,e,List.of(),null,false,false)).disposition())
                .isEqualTo(OrderPaymentEvidenceDecision.Disposition.REVIEW_REQUIRED);
    }
    @Test void checkpointCannotSilentlyIncreaseTheSavedAuthorizationCeiling() {
        var s=snapshot(2,5000,7);
        var checkpoint=new Checkpoint(1,10,20,2,CODEC.fingerprint(s),"11".repeat(32),"22".repeat(32));
        var e=signed(new Epoch(1,EPOCH,1,10,20,6000,0,Origin.LEGACY_CURRENT_CHECKPOINT,s,null,checkpoint,ZERO));
        var f=facts(e,List.of());
        var ps=List.of(new Payment(10,1,"PAYJP","CARD","AUTHORIZED",7000,"pfw_test"));
        var as=List.of(new Authorization(20,1,10,"AUTHORIZE","SUCCEEDED",7000,0,"pfw_test","INITIAL_ORDER_AUTHORIZATION",null));
        assertThat(validator.validate(copy(f,s,10L,ps,as,e,List.of(),null,false,false)).disposition())
                .isEqualTo(OrderPaymentEvidenceDecision.Disposition.REVIEW_REQUIRED);
    }
}
