package com.example.ecsite.service.order.evidence;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
public class OrderAdmissionFinancialSnapshotCodecTest {
    public static final String VECTOR = """
{"domain":"ec-site/order-admission/financial/v1","version":1,"admissionId":"80000000-0000-0000-0000-000000000001","orderId":1,"customerId":1,"dbGeneration":"80000000-0000-0000-0000-000000000001","originalXid":"18446744073709551615","requestDigest":"0000000000000000000000000000000000000000000000000000000000000000","candidateDigest":"0000000000000000000000000000000000000000000000000000000000000000","revision":1,"status":"日本語","deadline":-1,"epoch":"80000000-0000-0000-0000-000000000001","head":null,"fingerprint":"0000000000000000000000000000000000000000000000000000000000000000","epochDigest":"0000000000000000000000000000000000000000000000000000000000000000","headDigest":"0000000000000000000000000000000000000000000000000000000000000000","activePayment":1,"orderAmount":1,"payment":1,"authorization":1,"authorizedAmount":1,"authorizationRevision":1,"payments":[{"id":1,"orderId":1,"provider":"日本語","method":"日本語","status":"日本語","amount":1,"providerId":null}],"transactions":[{"id":1,"paymentId":1,"type":"日本語","status":"日本語","amount":1,"revision":1,"purpose":null,"operation":null,"providerId":null,"completed":"2026-10-31T01:02:03.000004"}],"operations":[{"id":"80000000-0000-0000-0000-000000000001","orderId":1,"state":"日本語","version":1,"baseRevision":1,"oldPayment":1,"newPayment":null,"beforeAmount":1,"afterAmount":1,"switchDecided":null,"history":null,"executionVersion":null,"route":null}],"requests":[{"id":1,"operation":"80000000-0000-0000-0000-000000000001","orderId":1,"payment":1,"transaction":1,"purpose":"日本語","kind":"日本語","type":"日本語","amount":1,"flow":null,"idempotency":"日本語","connection":"日本語","account":"日本語","mode":"日本語","origin":"日本語","method":"日本語","path":"日本語","contentType":"日本語","bodyVersion":1,"bodyDigest":"0000000000000000000000000000000000000000000000000000000000000000","fingerprintVersion":1,"fingerprint":"0000000000000000000000000000000000000000000000000000000000000000","created":-1,"replayUntil":-1}],"dispatches":[{"request":1,"state":"日本語","created":-1,"started":null,"observed":null}],"captures":[{"id":1,"payment":1,"transaction":1,"type":"日本語","provider":"日本語","method":"日本語","flow":"日本語","connection":"日本語","account":"日本語","mode":"日本語","origin":"日本語","httpMethod":"日本語","path":"日本語","contentType":"日本語","amount":1,"revision":1,"idempotency":"日本語","bodyVersion":1,"bodyDigest":"0000000000000000000000000000000000000000000000000000000000000000","fingerprintVersion":1,"fingerprint":"0000000000000000000000000000000000000000000000000000000000000000","created":-1,"replayUntil":-1}]}
        """;
    public static final String HEX = "010000002465632d736974652f6f726465722d61646d697373696f6e2f66696e616e6369616c2f763101000000040000000101000000108000000000000000000000000000000101000000080000000000000001010000000800000000000000010100000010800000000000000000000000000000010100000008ffffffffffffffff01000000200000000000000000000000000000000000000000000000000000000000000000010000002000000000000000000000000000000000000000000000000000000000000000000100000004000000010100000009e697a5e69cace8aa9e0100000008ffffffffffffffff01000000108000000000000000000000000000000100010000002000000000000000000000000000000000000000000000000000000000000000000100000020000000000000000000000000000000000000000000000000000000000000000001000000200000000000000000000000000000000000000000000000000000000000000000010000000800000000000000010100000004000000010100000008000000000000000101000000080000000000000001010000000400000001010000000400000001010000005700000001010000004e01000000080000000000000001010000000800000000000000010100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e01000000040000000100010000007300000001010000006a01000000080000000000000001010000000800000000000000010100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e010000000400000001010000000400000001000000010000001a323032362d31302d33315430313a30323a30332e303030303034010000007300000001010000006a010000001080000000000000000000000000000001010000000800000000000000010100000009e697a5e69cace8aa9e01000000080000000000000001010000000400000001010000000800000000000000010001000000040000000101000000040000000100000000010000016c000000010100000163010000000800000000000000010100000010800000000000000000000000000000010100000008000000000000000101000000080000000000000001010000000800000000000000010100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e010000000400000001000100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e01000000040000000101000000200000000000000000000000000000000000000000000000000000000000000000010000000400000001010000002000000000000000000000000000000000000000000000000000000000000000000100000008ffffffffffffffff0100000008ffffffffffffffff010000003300000001010000002a010000000800000000000000010100000009e697a5e69cace8aa9e0100000008ffffffffffffffff000001000001600000000101000001570100000008000000000000000101000000080000000000000001010000000800000000000000010100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000009e697a5e69cace8aa9e0100000004000000010100000004000000010100000009e697a5e69cace8aa9e01000000040000000101000000200000000000000000000000000000000000000000000000000000000000000000010000000400000001010000002000000000000000000000000000000000000000000000000000000000000000000100000008ffffffffffffffff0100000008ffffffffffffffff";
    public static final String DIGEST = "11a1f8943ccb58823587cf810a52e90f8c8d2fef3798002bfdcd3a138b13eac1";
    final OrderAdmissionFinancialSnapshotCodec codec=new OrderAdmissionFinancialSnapshotCodec();
    @Test void independentFixedVector(){
        var dto=codec.decode(VECTOR);
        assertThat(HexFormat.of().formatHex(codec.canonical(dto))).isEqualTo(HEX);
        assertThat(HexFormat.of().formatHex(codec.digest(dto))).isEqualTo(DIGEST);
        assertThat(codec.canonical(codec.decode(codec.audit(dto)))).isEqualTo(codec.canonical(dto));
    }
    @Test void rejectsUnknownDuplicateMissingAndInvalidTypes(){
        for(String bad:List.of(VECTOR.replace("\"version\":1","\"version\":2"),
            VECTOR.replace("\"version\":1","\"version\":1,\"version\":1"),
            VECTOR.replace("\"version\":1,",""),
            VECTOR.replace("\"version\":1","\"version\":1,\"unknown\":0"),
            VECTOR.replace("18446744073709551615","18446744073709551616"),
            VECTOR.replace("\"orderId\":1","\"orderId\":1.0"),
            VECTOR.replace("\"payments\":[","\"payments\":null,\"ignored\":[")))
            assertThatThrownBy(()->codec.decode(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void domainSeparationAndOneByteChanges(){
        assertThat(codec.digest(codec.decode(VECTOR.replace("日本語","日本語a")))).isNotEqualTo(codec.digest(codec.decode(VECTOR)));
        assertThatThrownBy(()->codec.decode(VECTOR.replace("/financial/v1","/commit-binding/v1"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nullEmptyAndUnicodeFormsRemainDistinct(){
        byte[] original=codec.digest(codec.decode(VECTOR));
        assertThat(codec.digest(codec.decode(VECTOR.replace("\"providerId\":null","\"providerId\":\"\"")))).isNotEqualTo(original);
        assertThat(codec.digest(codec.decode(VECTOR.replace("日本語"," 日本語 ")))).isNotEqualTo(original);
        assertThat(codec.digest(codec.decode(VECTOR.replace("日本語","é")))).isNotEqualTo(codec.digest(codec.decode(VECTOR.replace("日本語","e\u0301"))));
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var node=(tools.jackson.databind.node.ObjectNode)json.readTree(VECTOR);
        node.putArray("captures");assertThat(codec.digest(codec.decode(node.toString()))).isNotEqualTo(original);
    }
    @Test void duplicateAndUnsortedRowsAreRejectedWithoutReordering(){
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var node=(tools.jackson.databind.node.ObjectNode)json.readTree(VECTOR);
        var first=node.get("payments").get(0).deepCopy();var second=(tools.jackson.databind.node.ObjectNode)first.deepCopy();second.put("id",2);
        node.putArray("payments").add(first).add(second);
        assertThat(codec.decode(node.toString()).payments()).hasSize(2);
        node.putArray("payments").add(second).add(first);
        assertThatThrownBy(()->codec.decode(node.toString())).isInstanceOf(IllegalArgumentException.class);
        node.putArray("payments").add(first).add(first);
        assertThatThrownBy(()->codec.decode(node.toString())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void boundedUtf8StringsRejectOversizedMultibyteValues(){
        assertThatThrownBy(()->codec.decode(VECTOR.replace("日本語","あ".repeat(3000)))).isInstanceOf(IllegalArgumentException.class);
    }

}
