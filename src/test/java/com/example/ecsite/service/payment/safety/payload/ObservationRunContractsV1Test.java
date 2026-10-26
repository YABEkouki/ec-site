package com.example.ecsite.service.payment.safety.payload;

import com.example.ecsite.service.payment.safety.*;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ObservationRunContractsV1Test {
    static final UUID RUN=UUID.fromString("00000000-0000-0000-0000-000000000001");
    static ObservationRunRequest.Manifest manifest() {
        return new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.range(0,100),List.of(1L,10L),
            "contract-v1","classifier-v1",(short)1,CaptureEvidenceProfiles.none(),Map.of("profile","approved"),Map.of(),ObservationRunRequest.Policy.defaults());
    }
    static ObservationRunRequest request(){return new ObservationRunRequest(RUN,ObservationRunRequest.ExecutionType.DIAGNOSTIC,manifest(),ObservationRunRequest.Actor.system("observer"));}
    static ObservationRunRequest.Manifest targets(List<Long> ids){var m=manifest();return new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.range(0,Long.MAX_VALUE),ids,m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),m.verificationProfiles(),m.timeOrigins(),m.policy());}
    @Test void manifestRoundTripAndFixedRequestFlags() {
        var codec=new ObservationManifestCodecV1();var m=manifest();assertThat(codec.decode(codec.encode(m))).isEqualTo(m);
        assertThat(request().mode()).isEqualTo("OBSERVE_ONLY");assertThat(request().guardActivationRef()).isNull();
        assertThat(request().financialResumption()).isEqualTo("NOT_EVALUATED");
        assertThat(request()).isEqualTo(request());
    }
    @Test void identitiesDomainsAndJsonLayout() {
        var a=request();var b=new ObservationRunRequest(UUID.fromString("00000000-0000-0000-0000-000000000002"),a.executionType(),a.manifest(),a.requester());
        assertThat(ObservationRunDigestsV1.requestDigest(a)).isNotEqualTo(ObservationRunDigestsV1.requestDigest(b));
        assertThat(ObservationRunDigestsV1.manifestDigest(a.manifest())).isEqualTo(ObservationRunDigestsV1.manifestDigest(b.manifest()));
        assertThat(ObservationRunDigestsV1.requestDigest(a)).isNotEqualTo(ObservationRunDigestsV1.manifestDigest(a.manifest()));
        var codec=new ObservationManifestCodecV1();String json=codec.encode(a.manifest());
        String reordered=json.replace("{\"version\":1,\"format\":\"payment-safety-run-manifest\",", "{ \"format\":\"payment-safety-run-manifest\", \"version\":1, ");
        assertThat(codec.decode(reordered)).isEqualTo(a.manifest());
        assertThat(ObservationRunDigestsV1.manifestDigest(codec.decode(reordered))).isEqualTo(ObservationRunDigestsV1.manifestDigest(a.manifest()));
    }
    @Test void targetOrderingDuplicatesNullAndLimits() {
        for(var ids:List.of(List.of(2L,1L),List.of(1L,1L),List.of(0L),List.of(-1L)))
            assertThatThrownBy(()->targets(ids)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->targets(Arrays.asList(1L,null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(targets(LongStream.rangeClosed(1,1000).boxed().toList()).targetOrderIds()).hasSize(1000);
        assertThatThrownBy(()->targets(LongStream.rangeClosed(1,1001).boxed().toList())).isInstanceOf(IllegalArgumentException.class);
        assertThat(targets(List.of(Long.MAX_VALUE)).targetOrderIds()).containsExactly(Long.MAX_VALUE);
    }
    @ParameterizedTest @ValueSource(strings={"9223372036854775808","-9223372036854775809","01","+1","1.0"})
    void rejectsInvalidBigint(String value) {
        var codec=new ObservationManifestCodecV1();String json=codec.encode(manifest());
        assertThatThrownBy(()->codec.decode(json.replace("\"type\":\"int64\",\"value\":\"10\"","\"type\":\"int64\",\"value\":\""+value+"\""))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsVersionUnknownMissingDuplicateAndTrailingFields() {
        var codec=new ObservationManifestCodecV1();String json=codec.encode(manifest());
        for(String invalid:List.of(json.replace("\"version\":1","\"version\":2"),json.replace("\"version\":1","\"version\":4294967297"),
                json.replace("\"version\":1","\"version\":1,\"extra\":null"),json.replace("\"version\":1","\"version\":1,\"version\":1"),json+" {}",
                json.replace("\"policy\":{","\"policy\":{\"unknown\":null,")))
            assertThatThrownBy(()->codec.decode(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void profilesAndPoliciesAffectDigestsAndRetainApprovalContents() {
        var m=manifest();var profiles=new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("manifest","approval",Map.of(3L,new CaptureConnectionIdentity("primary","account","TEST","https://api.pay.jp"))),
            new CaptureEvidenceProfiles.TimestampBindingProfile("time","approval",Map.of(3L,"Asia/Tokyo")));
        var changed=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),profiles,m.verificationProfiles(),m.timeOrigins(),m.policy());
        var codec=new ObservationManifestCodecV1();assertThat(codec.decode(codec.encode(changed))).isEqualTo(changed);
        assertThat(ObservationRunDigestsV1.manifestDigest(changed)).isNotEqualTo(ObservationRunDigestsV1.manifestDigest(m));
        var policy=new ObservationRunRequest.Policy(180,20,3,1000,10,30,1000);
        var changedPolicy=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),m.verificationProfiles(),m.timeOrigins(),policy);
        assertThat(ObservationRunDigestsV1.manifestDigest(changedPolicy)).isNotEqualTo(ObservationRunDigestsV1.manifestDigest(m));
    }
    @Test void scopeAndActorAndMutableInputsAreValidated() {
        assertThatThrownBy(()->ObservationRunRequest.Scope.single(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationRunRequest.Scope.range(10,9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationRunRequest.Actor.admin(0,"admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationRunRequest.Actor.system("   ")).isInstanceOf(IllegalArgumentException.class);
        var ids=new ArrayList<>(List.of(1L));var m=targets(ids);ids.add(2L);assertThat(m.targetOrderIds()).containsExactly(1L);
        assertThatThrownBy(()->m.targetOrderIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        var original=manifest();assertThatThrownBy(()->new ObservationRunRequest.Manifest(ObservationRunRequest.Scope.single(1),List.of(),original.classificationContractVersion(),original.classifierImplVersion(),(short)1,original.captureProfiles(),Map.of(),Map.of(),original.policy())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void refusesOversizedManifestWithoutTruncation() {
        var m=manifest();var huge=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),Map.of("profile","x".repeat(1024*1024)),Map.of(),m.policy());
        assertThatThrownBy(()->new ObservationManifestCodecV1().encode(huge)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
    }
    @Test void eachPolicyFieldParticipatesAndInvalidPoliciesAreRejected() {
        var m=manifest();String original=ObservationRunDigestsV1.manifestDigest(m);
        for(var p:List.of(new ObservationRunRequest.Policy(121,20,3,1000,10,30,1000),new ObservationRunRequest.Policy(120,21,3,1000,10,30,1000),
                new ObservationRunRequest.Policy(120,20,4,1000,10,30,1000),new ObservationRunRequest.Policy(120,20,3,1001,10,30,1000),
                new ObservationRunRequest.Policy(120,20,3,1000,11,30,1000),new ObservationRunRequest.Policy(120,20,3,1000,10,31,1000),
                new ObservationRunRequest.Policy(120,20,3,1000,10,30,999))) {
            var changed=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),m.verificationProfiles(),m.timeOrigins(),p);
            assertThat(ObservationRunDigestsV1.manifestDigest(changed)).isNotEqualTo(original);
        }
        assertThatThrownBy(()->new ObservationRunRequest.Policy(120,120,3,1000,10,30,1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ObservationRunRequest.Policy(120,20,0,1000,10,30,1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ObservationRunRequest.Policy(120,20,3,1000,10,30,1001)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void profileContentsAndApprovalReferencesAreNotJustNames() {
        var m=manifest();var codec=new ObservationManifestCodecV1();var hashes=new HashSet<String>();
        for(String account:List.of("account1","account2")) {
            var profile=new CaptureEvidenceProfiles(new CaptureEvidenceProfiles.ConnectionManifest("same-id","same-approval",Map.of(3L,new CaptureConnectionIdentity("primary",account,"TEST","https://api.pay.jp"))),null);
            var changed=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),profile,m.verificationProfiles(),m.timeOrigins(),m.policy());
            assertThat(codec.decode(codec.encode(changed))).isEqualTo(changed);
            hashes.add(ObservationRunDigestsV1.manifestDigest(changed));
        }
        assertThat(hashes).hasSize(2);
    }
    @Test void clocksAndMapIterationOrderRoundTripWithoutInference() {
        var m=manifest();
        var ref=new com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeReference(com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeField.ORDER_PAID,1L);
        var origin=new com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeOrigin(com.example.ecsite.service.payment.PaymentSafetySnapshot.ClockKind.UNKNOWN," raw ","not-a-zone");
        var a=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),Map.of("a","1","z","2"),Map.of(ref,origin),m.policy());
        var map=new LinkedHashMap<String,String>();map.put("z","2");map.put("a","1");
        var b=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),map,Map.of(ref,origin),m.policy());
        var codec=new ObservationManifestCodecV1();assertThat(codec.decode(codec.encode(a))).isEqualTo(a);
        assertThat(ObservationRunDigestsV1.manifestDigest(a)).isEqualTo(ObservationRunDigestsV1.manifestDigest(b));
    }
    @Test void jsonbFormattingBudgetCanRejectACompactDocumentThatWouldFit() {
        var m=manifest();var codec=new ObservationManifestCodecV1();
        var base=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),Map.of("profile",""),Map.of(),m.policy());
        int free=ObservationManifestCodecV1.MAX_BYTES-codec.encode(base).getBytes(java.nio.charset.StandardCharsets.UTF_8).length-1;
        var boundary=new ObservationRunRequest.Manifest(m.scope(),m.targetOrderIds(),m.classificationContractVersion(),m.classifierImplVersion(),m.fingerprintFormatVersion(),m.captureProfiles(),Map.of("profile","x".repeat(free)),Map.of(),m.policy());
        assertThatThrownBy(()->codec.encode(boundary)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
    }
    @Test void nestedUnknownMissingNullAndMalformedUnicodeAreRejected() {
        var codec=new ObservationManifestCodecV1();String json=codec.encode(manifest());
        for(String invalid:List.of(json.replace("\"leaseSeconds\":{\"type\":\"int32\",\"value\":\"120\"},",""),
            json.replace("\"scope\":{","\"scope\":{\"extra\":null,"),json.replace("\"manifest\":{","\"manifest\":null,\"extra\":{"),
            json.replace("approved","\\u0000"),json.replace("approved","\\uD800")))
            assertThatThrownBy(()->codec.decode(invalid)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid payment safety payload");
    }
    @Test void digestVerificationRejectsTamperingAndActorChangesOnlyRequestDigest() {
        var a=request();var b=new ObservationRunRequest(a.requestId(),a.executionType(),a.manifest(),ObservationRunRequest.Actor.admin(7,"admin"));
        assertThat(ObservationRunDigestsV1.requestDigest(a)).isNotEqualTo(ObservationRunDigestsV1.requestDigest(b));
        ObservationRunDigestsV1.verifyRequest(a,ObservationRunDigestsV1.requestDigest(a));
        ObservationRunDigestsV1.verifyManifest(a.manifest(),ObservationRunDigestsV1.manifestDigest(a.manifest()));
        assertThatThrownBy(()->ObservationRunDigestsV1.verifyRequest(a,"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationRunDigestsV1.verifyManifest(a.manifest(),"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validatesTheDefensiveCopyRatherThanOnlyTheCallersIteration() {
        // A caller-owned collection can change between iteration and its copy operation.
        var inconsistent=new AbstractList<Long>() {
            @Override public Long get(int index){return index==0?1L:10L;}
            @Override public int size(){return 2;}
            @Override public Object[] toArray(){return new Object[]{10L,1L};}
        };
        assertThatThrownBy(()->targets(inconsistent)).isInstanceOf(IllegalArgumentException.class);
    }
}
