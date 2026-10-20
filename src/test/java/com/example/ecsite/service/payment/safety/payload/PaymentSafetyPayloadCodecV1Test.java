package com.example.ecsite.service.payment.safety.payload;

import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.payment.safety.*;
import com.example.ecsite.service.payment.safety.PaymentSafetyReadFacts.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PaymentSafetyPayloadCodecV1Test {
    final PaymentSafetyPayloadCodecV1 codec = new PaymentSafetyPayloadCodecV1();
    static <T> T row(Class<T> type, Object... pairs) {
        try {
            var values=new HashMap<String,Object>();
            for(int i=0;i<pairs.length;i+=2)values.put((String)pairs[i],pairs[i+1]);
            var cs=type.getRecordComponents();
            return type.getDeclaredConstructor(Arrays.stream(cs).map(RecordComponent::getType).toArray(Class<?>[]::new))
                .newInstance(Arrays.stream(cs).map(c->values.get(c.getName())).toArray());
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    static PaymentSafetyReadFacts facts() {
        var p=row(PaymentRow.class,"id",10L,"orderId",1L,"status"," Unknown é e\u0301 ","amount",100);
        return new PaymentSafetyReadFacts(row(OrderRow.class,"id",1L,"status","ORDERED","totalAmount",100,"contentRevision",1),
            List.of(p,p),List.of(row(TransactionRow.class,"purpose","FUTURE")),List.of(),
            List.of(row(CaptureRow.class,"requestBody",new Bytes(new byte[]{0,1,-1}),"createdAt",Instant.EPOCH)),
            Map.of("z","  raw ","a","unknown"),Map.of());
    }
    static AssessmentRecordDigestV1.Record assessment(PaymentSafetyReadFacts facts) {
        var ev=new CaptureRequestEvidenceVerifier().verify(facts,CaptureEvidenceProfiles.none());
        var result=new PaymentSafetyClassification(PaymentSafetyClassification.ActivePaymentDisposition.REVIEW,null,List.of(),
            List.of(new PaymentSafetyClassification.ReviewIssue(PaymentSafetyClassification.Reason.INVALID_INPUT,null,null,"raw evidence")),
            new PaymentSafetyClassification.ClassificationEvidence(null,List.of()));
        return new AssessmentRecordDigestV1.Record(UUID.fromString("00000000-0000-0000-0000-000000000001"),1L,1,"OBSERVED",null,
            "contract-v1","classifier-v1",(short)1,"a".repeat(64),facts,ev,result,
            new AssessmentRecordDigestV1.ObservedValues("ORDERED",100,1,null,0,1,"MISMATCH","NOT_EVALUATED"));
    }
    @Test void roundTripPreservesNullUnknownDuplicatesOrderBytesAndMaps() {
        var f=facts();assertThat(codec.decodeInput(codec.encodeInput(f))).isEqualTo(f);
        assertThat(codec.encodeInput(f)).contains("\"type\":\"int64\"","\"value\":null");
    }
    @Test void everyComponentOfEveryRawRowRoundTripsIncludingNumericAndTimeExtremes() {
        for(var type:List.of(OrderRow.class,PaymentRow.class,TransactionRow.class,HistoryRow.class,CaptureRow.class)) {
            for(var c:type.getRecordComponents()) {
                Object v=sample(c.getType());Object r=row(type,c.getName(),v);var f=facts();
                var changed=new PaymentSafetyReadFacts(type==OrderRow.class?(OrderRow)r:f.order(),
                    type==PaymentRow.class?List.of((PaymentRow)r):f.payments(),type==TransactionRow.class?List.of((TransactionRow)r):f.transactions(),
                    type==HistoryRow.class?List.of((HistoryRow)r):f.statusHistory(),type==CaptureRow.class?List.of((CaptureRow)r):f.captureRequests(),f.verificationProfiles(),f.timeOrigins());
                assertThat(codec.decodeInput(codec.encodeInput(changed))).as(type.getSimpleName()+"."+c.getName()).isEqualTo(changed);
            }
        }
    }
    static Object sample(Class<?> t) {
        if(t==Long.class)return Long.MIN_VALUE;if(t==Integer.class)return Integer.MAX_VALUE;if(t==Short.class)return Short.MIN_VALUE;
        if(t==String.class)return " é e\u0301\n raw ";if(t==LocalDateTime.class)return LocalDateTime.MIN;
        if(t==Instant.class)return Instant.MIN;if(t==Bytes.class)return new Bytes(new byte[]{-1,0,1});throw new AssertionError(t);
    }
    @ParameterizedTest @ValueSource(strings={"9223372036854775808","-9223372036854775809","01","+1","1.0","1e2"})
    void rejectsInvalidInt64(String n) { invalid(codec.encodeInput(facts()).replace("\"value\":\"1\"","\"value\":\""+n+"\"")); }
    @ParameterizedTest @ValueSource(strings={"2026-02-30T01:02:03","2026-01-01T24:00:00","2026-01-01T01:02:60","2026-01-01T00:00:00Z"})
    void rejectsInvalidLocalTime(String value) {
        var f=facts();var o=row(OrderRow.class,"orderedAt",LocalDateTime.of(2026,1,1,0,0));
        var changed=new PaymentSafetyReadFacts(o,f.payments(),f.transactions(),f.statusHistory(),f.captureRequests(),Map.of(),Map.of());
        invalid(codec.encodeInput(changed).replace("2026-01-01T00:00:00",value));
    }
    @ParameterizedTest @ValueSource(strings={"AAH/\n","AAH","AAH_","!!"})
    void rejectsNonCanonicalBase64(String value) {invalid(codec.encodeInput(facts()).replace("AAH/",value));}
    @Test void rejectsDuplicateObjectKeysUnknownVersionMissingAndExtraFields() {
        String json=codec.encodeInput(facts());
        invalid(json.replace("\"version\":1","\"version\":1,\"version\":1"));
        invalid(json.replace("\"version\":1","\"version\":2"));
        invalid(json.replace("\"version\":1","\"version\":4294967297"));
        invalid(json.replace("\"facts\":{","\"extra\":0,\"facts\":{"));
        invalid(json.replace("\"status\":{\"type\":\"text\",\"value\":\"ORDERED\"},",""));
        invalid(json+" {}");
    }
    @Test void rejectsDuplicateAndNullMapKeys() {
        String json=codec.encodeInput(facts());
        String entry="{\"key\":{\"type\":\"text\",\"value\":\"a\"},\"value\":{\"type\":\"text\",\"value\":\"unknown\"}}";
        invalid(json.replace(entry,entry+","+entry));
        invalid(json.replace("\"key\":{\"type\":\"text\",\"value\":\"a\"}","\"key\":{\"type\":\"text\",\"value\":null}"));
    }
    @Test void assessmentRoundTripAndTamperDetection() {
        var record=assessment(facts());var payloads=codec.encodeAssessment(record);
        assertThat(codec.decodeAssessment(record.header(),payloads)).isEqualTo(record);
        var bad=new PaymentSafetyPayloadCodecV1.Payloads(payloads.input(),payloads.classification(),payloads.evidence(),payloads.rawFingerprint(),payloads.finalFingerprint(),"0".repeat(64));
        assertThatThrownBy(()->codec.decodeAssessment(record.header(),bad)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
    }
    @Test void rowOrderChangesRecordDigestButNotExistingFingerprint() {
        var f=facts();var rows=List.of(row(PaymentRow.class,"id",10L),row(PaymentRow.class,"id",20L));
        var a=new PaymentSafetyReadFacts(f.order(),rows,List.of(),List.of(),List.of(),Map.of(),Map.of());
        var b=new PaymentSafetyReadFacts(f.order(),List.of(rows.get(1),rows.get(0)),List.of(),List.of(),List.of(),Map.of(),Map.of());
        assertThat(PaymentSafetyInputFingerprint.sha256(a)).isEqualTo(PaymentSafetyInputFingerprint.sha256(b));
        assertThat(AssessmentRecordDigestV1.sha256(assessment(a))).isNotEqualTo(AssessmentRecordDigestV1.sha256(assessment(b)));
    }
    @Test void sizeDepthTokensAndStringLimitsRejectWithoutLeakingInput() {
        var small=new PaymentSafetyPayloadCodecV1(new PaymentSafetyPayloadCodecV1.Limits(256,10,100,32,10));
        assertThatThrownBy(()->small.encodeInput(facts())).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
        assertThatThrownBy(()->small.decodeInput("[".repeat(12)+"0"+"]".repeat(12))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsInt32AndInt16OverflowAndWrongTypes() {
        String json=codec.encodeInput(facts());
        invalid(json.replace("\"totalAmount\":{\"type\":\"int32\",\"value\":\"100\"}","\"totalAmount\":{\"type\":\"int32\",\"value\":\"2147483648\"}"));
        invalid(json.replace("\"bodyFormatVersion\":{\"type\":\"int16\",\"value\":null}","\"bodyFormatVersion\":{\"type\":\"int16\",\"value\":\"32768\"}"));
        invalid(json.replace("\"type\":\"int64\"","\"type\":\"int32\""));
        invalid(json.replace("\"nano\":0","\"nano\":1000000000"));
        invalid(json.replace("\"nano\":0","\"nano\":0.0"));
    }
    @Test void checksBothFingerprintsIndependently() {
        var record=assessment(facts());var p=codec.encodeAssessment(record);
        var badRaw=new PaymentSafetyPayloadCodecV1.Payloads(p.input(),p.classification(),p.evidence(),"0".repeat(64),p.finalFingerprint(),p.recordDigest());
        var badFinal=new PaymentSafetyPayloadCodecV1.Payloads(p.input(),p.classification(),p.evidence(),p.rawFingerprint(),"0".repeat(64),p.recordDigest());
        assertThatThrownBy(()->codec.decodeAssessment(record.header(),badRaw)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.decodeAssessment(record.header(),badFinal)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void enforcesArrayTokenStringAndPayloadBudgetsIndividually() {
        String json=codec.encodeInput(facts());
        for(var limits:List.of(new PaymentSafetyPayloadCodecV1.Limits(100000,64,100000,10000,1),
            new PaymentSafetyPayloadCodecV1.Limits(100000,64,5,10000,100),
            new PaymentSafetyPayloadCodecV1.Limits(100000,64,100000,2,100),
            new PaymentSafetyPayloadCodecV1.Limits(100,64,100000,10000,100))) {
            var bounded=new PaymentSafetyPayloadCodecV1(limits);
            assertThatThrownBy(()->bounded.decodeInput(json)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
        }
    }
    @Test void malformedUnicodeAndJsonbNulAreRejectedWithoutSourceInException() {
        var f=facts();
        for(String raw:List.of("SECRET\uD800","SECRET\u0000")) {
            var changed=new PaymentSafetyReadFacts(row(OrderRow.class,"status",raw),f.payments(),f.transactions(),f.statusHistory(),f.captureRequests(),Map.of(),Map.of());
            assertThatThrownBy(()->codec.encodeInput(changed)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid payment safety payload").hasNoCause();
        }
    }
    @Test void doesNotRaiseTheReadersSixteenMiBRawBudget() {
        var f=facts();
        var tooLarge=new PaymentSafetyReadFacts(row(OrderRow.class,"status","x".repeat(16*1024*1024)),
            f.payments(),f.transactions(),f.statusHistory(),f.captureRequests(),Map.of(),Map.of());
        assertThatThrownBy(()->codec.encodeInput(tooLarge)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
    }
    @Test void overflowingVersionCannotAliasVersionOneInDetails() {
        var r=assessment(facts());var p=codec.encodeAssessment(r);
        var changed=new PaymentSafetyPayloadCodecV1.Payloads(p.input(),p.classification().replace("\"version\":1","\"version\":4294967297"),p.evidence(),p.rawFingerprint(),p.finalFingerprint(),p.recordDigest());
        assertThatThrownBy(()->codec.decodeAssessment(r.header(),changed)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oversizedBinaryIsRejectedAndNeverTruncated() {
        var f=facts();var bytes=new Bytes(new byte[16*1024*1024+1]);
        var tooLarge=new PaymentSafetyReadFacts(f.order(),f.payments(),f.transactions(),f.statusHistory(),
            List.of(row(CaptureRow.class,"requestBody",bytes)),Map.of(),Map.of());
        assertThatThrownBy(()->codec.encodeInput(tooLarge)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
        var small=new PaymentSafetyPayloadCodecV1(new PaymentSafetyPayloadCodecV1.Limits(256,64,100000,32,100));
        var normal=new PaymentSafetyReadFacts(f.order(),List.of(),List.of(),List.of(),List.of(row(CaptureRow.class,"requestBody",new Bytes(new byte[1024]))),Map.of(),Map.of());
        assertThatThrownBy(()->small.encodeInput(normal)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
    }
    @Test void payloadBudgetIsTheSumOfAllThreeDocuments() {
        var r=assessment(facts());var p=codec.encodeAssessment(r);
        int largest=Math.max(p.input().length(),Math.max(p.classification().length(),p.evidence().length()));
        var bounded=new PaymentSafetyPayloadCodecV1(new PaymentSafetyPayloadCodecV1.Limits(largest+100,64,100000,100000,1000));
        assertThatThrownBy(()->bounded.encodeAssessment(r)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->bounded.decodeAssessment(r.header(),p)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unknownAndConflictingPurposeResultsRemainUnconfirmed() {
        var a=assessment(facts());
        var purposes=List.of(new PaymentSafetyClassification.TransactionPurpose(0,null,PaymentSafetyClassification.PurposeDisposition.UNKNOWN,
            com.example.ecsite.entity.PaymentTransactionPurpose.LEGACY_UNKNOWN," FUTURE ","unknown"),
            new PaymentSafetyClassification.TransactionPurpose(1,null,PaymentSafetyClassification.PurposeDisposition.CONFLICT,
            com.example.ecsite.entity.PaymentTransactionPurpose.LEGACY_UNKNOWN,null,"conflict"));
        var c=new PaymentSafetyClassification(a.classification().disposition(),null,purposes,a.classification().reviewIssues(),a.classification().evidence());
        var b=new AssessmentRecordDigestV1.Record(a.runId(),a.orderId(),a.attemptNo(),a.phase(),a.preAssessmentId(),a.classificationContractVersion(),a.classifierImplVersion(),a.fingerprintFormatVersion(),a.contractManifestDigest(),a.facts(),a.evidence(),c,a.observed());
        assertThat(codec.decodeAssessment(b.header(),codec.encodeAssessment(b))).isEqualTo(b);
        assertThat(b.classification().financialResumption()).isEqualTo(PaymentSafetyClassification.FinancialResumption.NOT_EVALUATED);
    }
    void invalid(String json){assertThatThrownBy(()->codec.decodeInput(json)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");}
}
