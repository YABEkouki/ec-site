package com.example.ecsite.service.order.evidence;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.ecsite.service.order.evidence.OrderEditCandidate.*;

class OrderEditAdmissionPayloadCodecTest {
    final OrderEditAdmissionPayloadCodec codec=new OrderEditAdmissionPayloadCodec();
    @Test void requestHasFixedBytesAndInputOrderDoesNotMatter() {
        var r=new Request(1,0,List.of(new Line(null,8,1),new Line(3L,7,2),new Line(null,8,2)));
        String expected="{\"version\":1,\"kind\":\"ORDER_EDIT_ADMISSION_REQUEST\",\"customerId\":2,\"orderId\":1,\"revision\":0,\"lines\":[{\"existingItemId\":3,\"productId\":7,\"quantity\":2},{\"existingItemId\":null,\"productId\":8,\"quantity\":3}]}";
        assertThat(codec.request(2,r)).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(OrderEvidenceDigests.sha256(codec.request(2,r))).isEqualTo("585984369e19b75ecf64c3c8df168ed3cead052b21826c8280e48c1616d84f47");
        assertThat(codec.request(2,r)).isEqualTo(codec.request(2,new Request(1,0,List.of(new Line(null,8,3),new Line(3L,7,2)))));
        assertThat(codec.decodeRequest(codec.request(2,r))).isEqualTo(new OrderEditAdmissionPayloadCodec.RequestPayload(1,"ORDER_EDIT_ADMISSION_REQUEST",2,1,0,codec.normalize(r).lines()));
        assertThatThrownBy(()->codec.decodeRequest((expected+" ").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.decodeRequest(expected.replace("\"version\":1","\"version\":1,\"version\":1").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.decodeRequest(expected.replace("\"version\":1","\"version\":1,\"unknown\":1").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidAndOverflowingRequestsAreRejected() {
        for(var r:List.of(new Request(1,0,List.of(new Line(3L,7,0),new Line(3L,7,1))),
                new Request(1,0,List.of(new Line(null,8,-1))),
                new Request(1,0,List.of(new Line(null,8,Integer.MAX_VALUE),new Line(null,8,1)))))
            assertThatThrownBy(()->codec.request(2,r)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void candidateBindsEvidenceAndKeepsTemporaryKeysExplicit() {
        var fixture=new OrderEditCandidateCalculatorTest();
        var c=fixture.calculator.calculate(fixture.context(),fixture.request(new Line(3L,8,2)),List.of(fixture.offer(8,1000))).candidate();
        var bytes=codec.candidate(c);
        assertThat(new String(bytes,StandardCharsets.UTF_8)).contains("ORDER_EDIT_ADMISSION_CANDIDATE","newLineReferences","previewItemId","epochDigest");
        assertThat(codec.candidate(c)).isEqualTo(bytes);
        assertThat(codec.decodeCandidate(bytes).preview()).isEqualTo(c.previewSnapshot());
        assertThatThrownBy(()->codec.decodeCandidate(new String(bytes,StandardCharsets.UTF_8).replace("\"version\":1","\"version\":99").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void canonicalShapeDoesNotSubstituteForSavedRequestAndDeltaSemantics(){
        var f=new OrderEditCandidateCalculatorTest();var request=f.request(new Line(3L,8,2));
        var candidate=f.calculator.calculate(f.context(),request,List.of(f.offer(8,1000))).candidate();
        var r=codec.decodeRequest(codec.request(2,request));var p=codec.decodeCandidate(codec.candidate(candidate));
        assertThat(p.preview().charges().getFirst().name()).isEqualTo("送料・梱包料");
        assertThatCode(()->codec.validateSaved(r,p,f.context().facts().current())).doesNotThrowAnyException();
        byte[] altered=new String(codec.candidate(candidate),StandardCharsets.UTF_8).replace("\"beforeQuantity\":1","\"beforeQuantity\":99").getBytes(StandardCharsets.UTF_8);
        var malformed=codec.decodeCandidate(altered);
        assertThatThrownBy(()->codec.validateSaved(r,malformed,f.context().facts().current())).isInstanceOf(IllegalArgumentException.class);
        var other=codec.decodeRequest(codec.request(2,f.request(new Line(3L,8,1))));
        assertThatThrownBy(()->codec.validateSaved(other,p,f.context().facts().current())).isInstanceOf(IllegalArgumentException.class);
    }
}
