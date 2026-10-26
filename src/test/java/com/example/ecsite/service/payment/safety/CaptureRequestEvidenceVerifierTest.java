package com.example.ecsite.service.payment.safety;

import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.payment.safety.PaymentSafetyInputFingerprintTest.row;
import static com.example.ecsite.service.payment.PaymentSafetySnapshot.CaptureVerification.*;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.*;
import com.example.ecsite.payment.capture.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CaptureRequestEvidenceVerifierTest {
    static final LocalDateTime LOCAL=LocalDateTime.parse("2026-01-01T09:00:00.123456");
    static final Instant CREATED=Instant.parse("2026-01-01T00:00:00.123456Z");
    static final CaptureConnectionIdentity IDENTITY=new CaptureConnectionIdentity("primary","merchant","TEST","https://api.pay.jp");
    static final CaptureHttpRequest HTTP=CaptureHttpRequest.create(IDENTITY,"pfw_fixture",5500,"capture-key");
    static <T> T change(T record,String field,Object value) {
        var values=new ArrayList<Object>();
        try { for(var c:record.getClass().getRecordComponents()) {values.add(c.getName());values.add(c.getName().equals(field)?value:c.getAccessor().invoke(record));} }
        catch(ReflectiveOperationException e) {throw new AssertionError(e);}
        @SuppressWarnings("unchecked") var type=(Class<T>)record.getClass();return row(type,values.toArray());
    }
    static PaymentSafetyReadFacts fixture() {
        var order=row(OrderRow.class,"id",1L,"userId",1L,"status","SHIPPED","totalAmount",5500,"contentRevision",1,
            "shippedAt",LOCAL,"shippingPreparationStartedAt",LOCAL.minusMinutes(1),"shippingPreparationStartedByAccountId",42L,
            "shippingPreparationStartedByUsername","admin","shippedByAccountId",42L,"shippedByUsername","admin","shipmentCapturePaymentId",2L);
        var payment=row(PaymentRow.class,"id",2L,"orderId",1L,"provider","PAYJP","paymentMethod","CARD","status","AUTHORIZED","amount",7000,"providerPaymentId","pfw_fixture");
        var auth=row(TransactionRow.class,"id",3L,"paymentId",2L,"transactionType","AUTHORIZE","status","SUCCEEDED","amount",7000,"orderContentRevision",0,
            "idempotencyKey","auth-key","providerTransactionId","pfw_fixture","createdAt",LOCAL.minusHours(1),"completedAt",LOCAL.minusMinutes(59),"initiatorType","USER","initiatorId",1L,"initiatorUsername","user");
        var capture=row(TransactionRow.class,"id",4L,"paymentId",2L,"transactionType","CAPTURE","status","PENDING","amount",5500,"orderContentRevision",1,
            "idempotencyKey","capture-key","createdAt",LOCAL,"initiatorType","ADMIN","initiatorId",42L,"initiatorUsername","admin");
        var start=row(HistoryRow.class,"id",5L,"orderId",1L,"toStatus","ORDERED","changedAt",LOCAL.minusHours(1),"changedByType","USER","changedByAccountId",1L,"changedByUsername","user");
        var ship=row(HistoryRow.class,"id",6L,"orderId",1L,"fromStatus","ORDERED","toStatus","SHIPPED","changedAt",LOCAL.minusSeconds(1),"changedByType","ADMIN","changedByAccountId",42L,"changedByUsername","admin");
        var request=new CaptureRow(7L,2L,4L,"CAPTURE","PAYJP","CARD","pfw_fixture","primary","merchant","TEST","https://api.pay.jp","POST",HTTP.path(),HTTP.contentType(),5500,1,"capture-key",(short)1,new Bytes(HTTP.body()),(short)1,new Bytes(HTTP.fingerprint()),CREATED,CREATED.plusSeconds(23*3600));
        return new PaymentSafetyReadFacts(order,List.of(payment),List.of(auth,capture),List.of(start,ship),List.of(request),Map.of(),Map.of());
    }
    static CaptureEvidenceProfiles approved() {
        return new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("manifest-v1","approval-123",Map.of(4L,IDENTITY)),
            new CaptureEvidenceProfiles.TimestampBindingProfile("feature123-created-shipped-v1","approval-456",Map.of(4L,"Asia/Tokyo")));
    }
    CaptureEvidenceValidation verify(PaymentSafetyReadFacts f,CaptureEvidenceProfiles p) {return new CaptureRequestEvidenceVerifier().verify(f,p);}
    @Test void verifiesNormalAndDecreasedOldRequestWithoutPostPermissionOrClockInference() {
        var f=fixture();var v=verify(f,approved());assertThat(v.captures().getFirst().verification()).isEqualTo(VERIFIED);
        var assembled=new PaymentSafetySnapshotAssembler().assemble(f,approved());
        var result=new PaymentSafetyClassifier().classify(assembled.snapshot());
        assertThat(result.disposition()).isEqualTo(PaymentSafetyClassification.ActivePaymentDisposition.SELECTED);
        assertThat(result.financialResumption()).isEqualTo(PaymentSafetyClassification.FinancialResumption.NOT_EVALUATED);
        assertThat(assembled.snapshot().timeOrigins()).isEmpty();
        assertThat(result.evidence().timeComparisons()).allMatch(c->c.outcome()==PaymentSafetyClassification.TimeComparisonOutcome.NOT_COMPARABLE);
    }
    @Test void missingManifestOrTimeBindingCannotBeVerified() {
        for(var p:List.of(CaptureEvidenceProfiles.none(),new CaptureEvidenceProfiles(approved().connectionManifest(),null),new CaptureEvidenceProfiles(null,approved().timestampBinding()))) {
            assertThat(verify(fixture(),p).captures().getFirst().verification()).isEqualTo(UNVERIFIED);
            assertThat(new PaymentSafetyClassifier().classify(new PaymentSafetySnapshotAssembler().assemble(fixture(),p).snapshot()).disposition()).isEqualTo(PaymentSafetyClassification.ActivePaymentDisposition.REVIEW);
        }
    }
    @Test void noRequestIsMissingAndDuplicateRequestsMismatch() {
        var f=fixture();assertThat(verify(change(f,"captureRequests",List.of()),approved()).captures().getFirst().verification()).isEqualTo(MISSING);
        assertThat(verify(change(f,"captureRequests",List.of(f.captureRequests().getFirst(),f.captureRequests().getFirst())),approved()).captures().getFirst().verification()).isEqualTo(MISMATCH);
    }
    @ParameterizedTest @ValueSource(strings={"paymentId","providerFlowId","amountToCapture","orderContentRevision","idempotencyKey","httpMethod","apiPath","contentType","bodyFormatVersion","requestBody","fingerprintVersion","requestFingerprint","connectionId","providerAccountRef","mode","apiOrigin","transactionType","provider","paymentMethod","createdAt","replayNotAfter"})
    void rejectsCorruptedStoredFields(String field) {
        Object value=switch(field) {
            case "paymentId" -> 999L;case "amountToCapture" -> 5501;case "orderContentRevision" -> 2;
            case "bodyFormatVersion","fingerprintVersion" -> (short)2;
            case "requestBody" -> new Bytes(new byte[]{1});case "requestFingerprint" -> new Bytes(new byte[32]);
            case "createdAt" -> CREATED.plusNanos(1);case "replayNotAfter" -> CREATED.plusSeconds(24*3600);
            default -> "different";
        };
        var f=fixture();f=change(f,"captureRequests",List.of(change(f.captureRequests().getFirst(),field,value)));
        assertThat(verify(f,approved()).captures().getFirst().verification()).isEqualTo(MISMATCH);
        // Known contradictions win even when an approval is unavailable.
        assertThat(verify(f,CaptureEvidenceProfiles.none()).captures().getFirst().verification()).isEqualTo(MISMATCH);
    }
    @Test void connectionApprovalMismatchIsNotGuessedFromEnvironment() {
        var wrong=new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("manifest","approval",Map.of(4L,new CaptureConnectionIdentity("primary","other","LIVE","https://api.pay.jp"))),approved().timestampBinding());
        assertThat(verify(fixture(),wrong).captures().getFirst().verification()).isEqualTo(MISMATCH);
    }
    @Test void timestampBindingUsesApprovedScopeAndRejectsContradiction() {
        var f=fixture();var missingScope=new CaptureEvidenceProfiles(approved().connectionManifest(),new CaptureEvidenceProfiles.TimestampBindingProfile("v1","approval",Map.of(999L,"Asia/Tokyo")));
        assertThat(verify(f,missingScope).captures().getFirst().verification()).isEqualTo(UNVERIFIED);
        var shifted=change(f,"transactions",List.of(f.transactions().getFirst(),change(f.transactions().getLast(),"createdAt",LOCAL.plusSeconds(1))));
        assertThat(verify(shifted,approved()).captures().getFirst().verification()).isEqualTo(MISMATCH);
        assertThat(verify(shifted,new CaptureEvidenceProfiles(approved().connectionManifest(),null)).captures().getFirst().verification()).isEqualTo(UNVERIFIED);
    }
    @Test void orphanRequestAndDuplicateTransactionNeverDisappearIntoNormalSnapshot() {
        var f=fixture();var orphan=change(f,"transactions",List.of(f.transactions().getFirst()));
        assertThat(verify(orphan,approved()).globalFindings()).isNotEmpty();
        assertThat(new PaymentSafetyClassifier().classify(new PaymentSafetySnapshotAssembler().assemble(orphan,approved()).snapshot()).disposition()).isEqualTo(PaymentSafetyClassification.ActivePaymentDisposition.REVIEW);
        var dup=change(f,"transactions",List.of(f.transactions().getFirst(),f.transactions().getLast(),f.transactions().getLast()));
        assertThat(verify(dup,approved()).captures()).hasSize(2).allMatch(a->a.verification()==MISMATCH);
    }
    @Test void nullRequestFieldsAreContradictionsNotGuessedDefaults() {
        var f=fixture();
        for(String field:List.of("id","paymentId","amountToCapture","orderContentRevision","requestBody","requestFingerprint","createdAt","replayNotAfter")) {
            var invalid=change(f,"captureRequests",List.of(change(f.captureRequests().getFirst(),field,null)));
            assertThat(verify(invalid,approved()).captures().getFirst().verification()).as(field).isEqualTo(MISMATCH);
        }
    }
    @Test void ambiguousLocalTimeIsUnverifiedRatherThanAChosenOffset() {
        var f=fixture();var ambiguous=LocalDateTime.parse("2026-11-01T01:30:00");
        f=change(change(f,"order",change(f.order(),"shippedAt",ambiguous)),"transactions",List.of(f.transactions().getFirst(),change(f.transactions().getLast(),"createdAt",ambiguous)));
        var profile=new CaptureEvidenceProfiles(approved().connectionManifest(),new CaptureEvidenceProfiles.TimestampBindingProfile("dst-proof","approval",Map.of(4L,"America/New_York")));
        assertThat(verify(f,profile).captures().getFirst().verification()).isEqualTo(UNVERIFIED);
    }
    @Test void ambiguousInstantCannotHideContradictoryLocalValues() {
        var f=fixture();var ambiguous=LocalDateTime.parse("2026-11-01T01:30:00");
        f=change(change(f,"order",change(f.order(),"shippedAt",ambiguous.plusMinutes(1))),"transactions",List.of(f.transactions().getFirst(),change(f.transactions().getLast(),"createdAt",ambiguous)));
        var profile=new CaptureEvidenceProfiles(approved().connectionManifest(),new CaptureEvidenceProfiles.TimestampBindingProfile("dst-proof","approval",Map.of(4L,"America/New_York")));
        assertThat(verify(f,profile).captures().getFirst().verification()).isEqualTo(MISMATCH);
    }
    @Test void unsupportedTimestampEncodingCannotBeInterpretedAsFeature123V1() {
        var profile=new CaptureEvidenceProfiles(approved().connectionManifest(),
            new CaptureEvidenceProfiles.TimestampBindingProfile(99,"future-profile","approval",Map.of(4L,"Asia/Tokyo")));
        assertThat(verify(fixture(),profile).captures().getFirst().verification()).isEqualTo(UNVERIFIED);
    }
    @Test void unknownTypeRemainsRawAndCannotUseAnOrphanRequestAsProof() {
        var f=fixture();f=change(f,"transactions",List.of(f.transactions().getFirst(),change(f.transactions().getLast(),"transactionType","FUTURE_TYPE")));
        var a=new PaymentSafetySnapshotAssembler().assemble(f,approved());
        assertThat(a.snapshot().transactions().getLast().type()).isEqualTo("FUTURE_TYPE");
        assertThat(a.evidence().globalFindings()).isNotEmpty();
        assertThat(new PaymentSafetyClassifier().classify(a.snapshot()).disposition()).isEqualTo(PaymentSafetyClassification.ActivePaymentDisposition.REVIEW);
    }
    @Test void incompletePrimitiveDataThrowsInsteadOfBeingDefaulted() {
        var f=fixture();var invalid=change(f,"order",change(f.order(),"totalAmount",null));
        assertThatThrownBy(()->new PaymentSafetySnapshotAssembler().assemble(invalid,approved())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->verify(null,approved())).isInstanceOf(NullPointerException.class);
    }
    @Test void allRowsAndDeclaredUnknownOrConflictingPurposesAreRetained() {
        var f=fixture();var rows=new ArrayList<>(f.transactions());rows.set(0,change(rows.getFirst(),"purpose","LEGACY_UNKNOWN"));rows.set(1,change(rows.getLast(),"purpose","INITIAL_ORDER_AUTHORIZATION"));
        var p=new ArrayList<>(f.payments());p.add(change(p.getFirst(),"id",99L));
        var a=new PaymentSafetySnapshotAssembler().assemble(change(change(f,"transactions",rows),"payments",p),approved());
        assertThat(a.snapshot().payments()).hasSize(2);assertThat(a.snapshot().transactions()).hasSize(2);assertThat(a.snapshot().statusHistory()).hasSize(2);
        var result=new PaymentSafetyClassifier().classify(a.snapshot());
        assertThat(result.transactionPurposes()).extracting(t->t.disposition()).contains(PaymentSafetyClassification.PurposeDisposition.UNKNOWN,PaymentSafetyClassification.PurposeDisposition.CONFLICT);
    }
    @Test void finalFingerprintIncludesActualProfilesEvidenceBytesAndOriginsAndIsCanonical() {
        var assembler=new PaymentSafetySnapshotAssembler();var f=fixture();var a=assembler.assemble(f,approved());
        assertThat(a.inputFingerprint()).isEqualTo(assembler.assemble(f,approved()).inputFingerprint()).hasSize(64);
        assertThat(a.inputFingerprint()).isNotEqualTo(PaymentSafetyInputFingerprint.sha256(f));
        assertThat(a.inputFingerprint()).isNotEqualTo(assembler.assemble(f,CaptureEvidenceProfiles.none()).inputFingerprint());
        var renamed=new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("manifest-v2","new-approval",approved().connectionManifest().identitiesByTransaction()),approved().timestampBinding());
        assertThat(a.inputFingerprint()).isNotEqualTo(assembler.assemble(f,renamed).inputFingerprint());
        var rawEvidence=a.evidence();var entry=rawEvidence.captures().getFirst();
        var changedEvidence=new CaptureEvidenceValidation(rawEvidence.profiles(),List.of(new CaptureEvidenceValidation.CaptureAssessment(entry.transaction(),entry.requests(),UNVERIFIED,entry.findings())),rawEvidence.globalFindings());
        assertThat(a.inputFingerprint()).isNotEqualTo(PaymentSafetyInputFingerprint.sha256Final(f,changedEvidence));
        var ref=new PaymentSafetySnapshot.TimeReference(PaymentSafetySnapshot.TimeField.TRANSACTION_CREATED,4L);
        var unknown=new PaymentSafetySnapshot.TimeOrigin(PaymentSafetySnapshot.ClockKind.UNKNOWN,null,null);
        assertThat(a.inputFingerprint()).isNotEqualTo(assembler.assemble(change(f,"timeOrigins",Map.of(ref,unknown)),approved()).inputFingerprint());
        var reversed=change(change(f,"transactions",List.of(f.transactions().getLast(),f.transactions().getFirst())),"statusHistory",List.of(f.statusHistory().getLast(),f.statusHistory().getFirst()));
        assertThat(a.inputFingerprint()).isEqualTo(assembler.assemble(reversed,approved()).inputFingerprint());
    }
}
