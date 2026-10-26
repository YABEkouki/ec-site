package com.example.ecsite.service.payment.safety.payload;

import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.payment.safety.*;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CanonicalDigestV1Test {
    static AuditEventDigestV1.Record event(String payload) {
        return new AuditEventDigestV1.Record("RUN","ORDER_STARTED",UUID.fromString("00000000-0000-0000-0000-000000000001"),
            null,null,1L,1,1L,null,null,null,null,null,null,"SYSTEM",null,"observer",Instant.EPOCH,
            null,null,null,null,null,(short)1,payload);
    }
    @Test void goldenAssessmentDigest() {
        assertThat(AssessmentRecordDigestV1.sha256(PaymentSafetyPayloadCodecV1Test.assessment(PaymentSafetyPayloadCodecV1Test.facts())))
            .isEqualTo("9842ced7bbe169de8768893a2438afe5498e87ca9de0a26ea70a2afd401600cc");
    }
    @Test void goldenEventDigest() {assertThat(AuditEventDigestV1.sha256(event("{\"version\":1,\"values\":[null,\"unknown\",1]}"))).isEqualTo("4f743dc6bb568a4debf433c349a0b90ce37be0c987c654c11721ebffbe2b36d1");}
    @Test void recordComponentContractChangesRequireExplicitCodecVersionReview() throws Exception {
        var types=new ArrayList<Class<?>>();
        for(var root:List.of(PaymentSafetyReadFacts.class,PaymentSafetySnapshot.class,PaymentSafetyClassification.class,CaptureEvidenceValidation.class,CaptureEvidenceProfiles.class)) {
            if(root.isRecord())types.add(root);for(var nested:root.getDeclaredClasses())if(nested.isRecord())types.add(nested);
        }
        types.add(CaptureConnectionIdentity.class);types.sort(Comparator.comparing(Class::getName));
        var signature=new StringBuilder();for(var t:types){signature.append(t.getName()).append('\n');for(var c:t.getRecordComponents())signature.append(c.getName()).append(':').append(c.getGenericType().getTypeName()).append('\n');}
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature.toString().getBytes(StandardCharsets.UTF_8)))).isEqualTo("1604df14333012da61262b77b6a7d0546e65c2d9c8bc4afa5361daf24723a8ca");
    }
    @Test void eventObjectOrderAndWhitespaceDoNotMatterButArrayOrderTypesNullAndDuplicatesDo() {
        assertThat(AuditEventDigestV1.sha256(event("{\"version\":1,\"a\":2}")))
            .isEqualTo(AuditEventDigestV1.sha256(event("{ \"a\":2, \"version\":1 }")));
        var hashes=new HashSet<String>();
        for(String p:List.of("[1,2]","[2,1]","[1,1,2]","[\"1\",2]","[null,2]"))
            hashes.add(AuditEventDigestV1.sha256(event("{\"version\":1,\"a\":"+p+"}")));
        assertThat(hashes).hasSize(5);
        assertThat(AuditEventDigestV1.sha256(event("{\"version\":1,\"a\":1e2}")))
            .isEqualTo(AuditEventDigestV1.sha256(event("{\"version\":1,\"a\":100.0}")));
    }
    @Test void eventDetectsChangedBusinessFieldsAndTamperedDigest() {
        var a=event("{\"version\":1}");var b=event("{\"version\":1,\"x\":null}");
        String hash=AuditEventDigestV1.sha256(a);assertThat(hash).hasSize(64).isEqualTo(AuditEventDigestV1.sha256(a));
        assertThatThrownBy(()->AuditEventDigestV1.verify(b,hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->event("{\"version\":1,\"version\":1}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->event("{\"version\":4294967297}")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void classificationChangeChangesDigest() {
        var a=PaymentSafetyPayloadCodecV1Test.assessment(PaymentSafetyPayloadCodecV1Test.facts());
        var c=new PaymentSafetyClassification(PaymentSafetyClassification.ActivePaymentDisposition.NORMAL_NULL,null,List.of(),List.of(),a.classification().evidence());
        var b=new AssessmentRecordDigestV1.Record(a.runId(),a.orderId(),a.attemptNo(),a.phase(),a.preAssessmentId(),a.classificationContractVersion(),a.classifierImplVersion(),a.fingerprintFormatVersion(),a.contractManifestDigest(),a.facts(),a.evidence(),c,a.observed());
        assertThat(AssessmentRecordDigestV1.sha256(a)).isNotEqualTo(AssessmentRecordDigestV1.sha256(b));
    }
    @Test void profilesAndAllClockOriginsRoundTripAndAffectFinalHash() {
        var f=PaymentSafetyPayloadCodecV1Test.facts();
        var ref=new PaymentSafetySnapshot.TimeReference(PaymentSafetySnapshot.TimeField.ORDER_SHIPPED,1L);
        var origins=Map.of(ref,new PaymentSafetySnapshot.TimeOrigin(PaymentSafetySnapshot.ClockKind.UNKNOWN," raw ","not-a-zone"));
        f=new PaymentSafetyReadFacts(f.order(),f.payments(),f.transactions(),f.statusHistory(),f.captureRequests(),f.verificationProfiles(),origins);
        var a=PaymentSafetyPayloadCodecV1Test.assessment(f);
        var connection=new CaptureConnectionIdentity("fixture","account","TEST","https://api.pay.jp");
        var profiles=new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("manifest","approval",Map.of(7L,connection)),
            new CaptureEvidenceProfiles.TimestampBindingProfile("time","approval",Map.of(7L,"Asia/Tokyo")));
        var e=new CaptureRequestEvidenceVerifier().verify(f,profiles);
        var b=new AssessmentRecordDigestV1.Record(a.runId(),a.orderId(),a.attemptNo(),a.phase(),a.preAssessmentId(),a.classificationContractVersion(),a.classifierImplVersion(),a.fingerprintFormatVersion(),a.contractManifestDigest(),f,e,a.classification(),a.observed());
        var codec=new PaymentSafetyPayloadCodecV1();var p=codec.encodeAssessment(b);
        assertThat(codec.decodeAssessment(b.header(),p)).isEqualTo(b);
        assertThat(p.finalFingerprint()).isNotEqualTo(codec.encodeAssessment(a).finalFingerprint());
        assertThat(p.recordDigest()).isNotEqualTo(codec.encodeAssessment(a).recordDigest());
    }
    @Test void bodyByteDifferenceAndMapInsertionOrderHaveExpectedDigestSemantics() {
        var f=PaymentSafetyPayloadCodecV1Test.facts();
        var changed=new PaymentSafetyReadFacts(f.order(),f.payments(),f.transactions(),f.statusHistory(),
            List.of(PaymentSafetyPayloadCodecV1Test.row(CaptureRow.class,"requestBody",new Bytes(new byte[]{0,1,-2}),"createdAt",Instant.EPOCH)),f.verificationProfiles(),f.timeOrigins());
        assertThat(AssessmentRecordDigestV1.sha256(PaymentSafetyPayloadCodecV1Test.assessment(f)))
            .isNotEqualTo(AssessmentRecordDigestV1.sha256(PaymentSafetyPayloadCodecV1Test.assessment(changed)));
        var map=new LinkedHashMap<String,String>();map.put("z","  raw ");map.put("a","unknown");
        var reordered=new PaymentSafetyReadFacts(f.order(),f.payments(),f.transactions(),f.statusHistory(),f.captureRequests(),map,f.timeOrigins());
        assertThat(AssessmentRecordDigestV1.sha256(PaymentSafetyPayloadCodecV1Test.assessment(f)))
            .isEqualTo(AssessmentRecordDigestV1.sha256(PaymentSafetyPayloadCodecV1Test.assessment(reordered)));
    }
    @Test void rejectsHugeDecimalExponentBeforeCanonicalExpansion() {
        assertThatThrownBy(()->event("{\"version\":1,\"number\":1e2147483647}"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
    }
    @Test void eventDecimalPrecisionIsPreserved() {
        assertThat(AuditEventDigestV1.sha256(event("{\"version\":1,\"number\":0.123456789012345678901}")))
            .isNotEqualTo(AuditEventDigestV1.sha256(event("{\"version\":1,\"number\":0.123456789012345678902}")));
    }
    @Test void nonemptyCaptureFindingsAndClassificationTimeComparisonsRoundTrip() {
        var f=PaymentSafetyPayloadCodecV1Test.facts();
        var tx=PaymentSafetyPayloadCodecV1Test.row(TransactionRow.class,"id",7L,"paymentId",10L,"transactionType","CAPTURE","amount",100,"orderContentRevision",1);
        f=new PaymentSafetyReadFacts(f.order(),f.payments(),List.of(tx),f.statusHistory(),f.captureRequests(),f.verificationProfiles(),f.timeOrigins());
        var a=PaymentSafetyPayloadCodecV1Test.assessment(f);
        var time=new PaymentSafetyClassification.TimeComparison(new PaymentSafetySnapshot.TimeReference(PaymentSafetySnapshot.TimeField.ORDER_PAID,1L),
            LocalDateTime.of(2026,1,1,0,0),PaymentSafetySnapshot.TimeOrigin.unknown(),null,null,null,
            PaymentSafetyClassification.TimeComparisonOutcome.NOT_COMPARABLE,PaymentSafetyClassification.TimeComparisonReason.UNKNOWN_CLOCK);
        var c=new PaymentSafetyClassification(a.classification().disposition(),null,List.of(),a.classification().reviewIssues(),
            new PaymentSafetyClassification.ClassificationEvidence(new PaymentSafetySnapshot.ShippingPreparationFacts(null,2L,"actor"),List.of(time)));
        var b=new AssessmentRecordDigestV1.Record(a.runId(),a.orderId(),a.attemptNo(),a.phase(),a.preAssessmentId(),a.classificationContractVersion(),a.classifierImplVersion(),a.fingerprintFormatVersion(),a.contractManifestDigest(),f,a.evidence(),c,a.observed());
        assertThat(b.evidence().captures()).hasSize(1);
        var codec=new PaymentSafetyPayloadCodecV1();assertThat(codec.decodeAssessment(b.header(),codec.encodeAssessment(b))).isEqualTo(b);
    }
    @Test void eventRejectsSubmicrosecondOccurredAtInsteadOfSilentlyRounding() {
        var e=event("{\"version\":1}");
        assertThatThrownBy(()->new AuditEventDigestV1.Record(e.eventScope(),e.eventType(),e.runId(),e.caseId(),e.orderId(),e.targetOrderId(),e.attemptNo(),e.leaseEpoch(),e.assessmentId(),e.caseVersionBefore(),e.caseVersionAfter(),e.fromStatus(),e.toStatus(),e.outcome(),e.actorType(),e.actorAccountId(),e.actorUsername(),Instant.ofEpochSecond(0,1),e.comment(),e.errorStage(),e.errorCode(),e.sqlState(),e.errorSummary(),e.payloadFormatVersion(),e.payloadJson()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
    }
    @Test void profileContentChangeWithIdenticalApprovalIdsStillChangesFingerprintAndDigest() {
        var a=PaymentSafetyPayloadCodecV1Test.assessment(PaymentSafetyPayloadCodecV1Test.facts());
        var identity1=new CaptureConnectionIdentity("fixture","account1","TEST","https://api.pay.jp");
        var identity2=new CaptureConnectionIdentity("fixture","account2","TEST","https://api.pay.jp");
        var p1=new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("same-id","same-approval",Map.of(7L,identity1)),null);
        var p2=new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("same-id","same-approval",Map.of(7L,identity2)),null);
        var e1=new CaptureRequestEvidenceVerifier().verify(a.facts(),p1);var e2=new CaptureRequestEvidenceVerifier().verify(a.facts(),p2);
        var r1=new AssessmentRecordDigestV1.Record(a.runId(),a.orderId(),a.attemptNo(),a.phase(),a.preAssessmentId(),a.classificationContractVersion(),a.classifierImplVersion(),a.fingerprintFormatVersion(),a.contractManifestDigest(),a.facts(),e1,a.classification(),a.observed());
        var r2=new AssessmentRecordDigestV1.Record(a.runId(),a.orderId(),a.attemptNo(),a.phase(),a.preAssessmentId(),a.classificationContractVersion(),a.classifierImplVersion(),a.fingerprintFormatVersion(),a.contractManifestDigest(),a.facts(),e2,a.classification(),a.observed());
        assertThat(PaymentSafetyInputFingerprint.sha256Final(a.facts(),e1)).isNotEqualTo(PaymentSafetyInputFingerprint.sha256Final(a.facts(),e2));
        assertThat(AssessmentRecordDigestV1.sha256(r1)).isNotEqualTo(AssessmentRecordDigestV1.sha256(r2));
    }
}
