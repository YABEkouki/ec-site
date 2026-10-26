package com.example.ecsite.service.orderchange;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Internal preparation evidence has no connected production caller. Never starts Spring or HTTP. */
class RequestedAbortEvidenceTest {
    private static final UUID OP=UUID.fromString("7e3d0b0c-f0c6-4ce5-8f90-b3d5900bb6b6");
    private static final CaptureConnectionIdentity CONNECTION=new CaptureConnectionIdentity("test-capture","test-account","TEST","https://api.pay.jp");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    ObjectNode body(){return (ObjectNode)JSON.readTree("""
        {"id":"pfw_new","status":"requires_capture","amount":910,"currency":"jpy","capture_method":"manual",
         "livemode":false,"amount_received":null,"amount_capturable":910,
         "metadata":{"order_id":"1","order_change_operation_id":"7e3d0b0c-f0c6-4ce5-8f90-b3d5900bb6b6"}}
        """);}
    void verify(ObjectNode body){OrderReauthorizationTransactions.RequestedAbortEvidence.verify(1,OP,2,"pfw_new",910,CONNECTION,body);}
    @Test void acceptsOnlyFullUncapturedAuthorization(){var body=body();verify(body);body.put("amount_received",0);verify(body);}
    static Stream<Arguments> rejected(){return Stream.of(
        Arguments.of("id","\"pfw_old\""),Arguments.of("status","\"requires_payment_method\""),Arguments.of("status","\"processing\""),
        Arguments.of("status","\"canceled\""),Arguments.of("status","\"succeeded\""),Arguments.of("amount","\"910\""),
        Arguments.of("amount","730"),Arguments.of("currency","\"JPY\""),Arguments.of("capture_method","\"automatic\""),
        Arguments.of("livemode","true"),Arguments.of("livemode","\"false\""),Arguments.of("amount_received","1"),
        Arguments.of("amount_received","\"0\""),Arguments.of("amount_received","0.0"),
        Arguments.of("amount_capturable","null"),Arguments.of("amount_capturable","909"),Arguments.of("amount_capturable","911"),
        Arguments.of("amount_capturable","\"910\""),Arguments.of("amount_capturable","9223372036854775808"),
        Arguments.of("metadata","{}"),Arguments.of("metadata","{\"order_id\":1}"));}
    @ParameterizedTest @MethodSource("rejected") void rejectsUnconfirmedMismatchedOrMistypedEvidence(String field,String value){
        var body=body();body.set(field,JSON.readTree(value));
        assertThatIllegalArgumentException().isThrownBy(()->verify(body));
    }
    @Test void requiresPresenceForReceivedAndCapturable(){
        for(String key:new String[]{"amount_received","amount_capturable"}){var body=body();body.remove(key);assertThatIllegalArgumentException().isThrownBy(()->verify(body));}
    }
}
