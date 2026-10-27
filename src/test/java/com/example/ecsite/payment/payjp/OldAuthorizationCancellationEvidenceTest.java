package com.example.ecsite.payment.payjp;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;

class OldAuthorizationCancellationEvidenceTest {
    final CaptureConnectionIdentity connection=new CaptureConnectionIdentity("test","account","TEST","https://api.pay.jp");
    String body(String received,String capturable){return "{\"id\":\"pfw_old\",\"status\":\"canceled\",\"amount\":730,\"currency\":\"jpy\",\"capture_method\":\"manual\",\"livemode\":false,\"amount_received\":"+received+",\"amount_capturable\":"+capturable+"}";}
    boolean valid(String json){return OldAuthorizationCancellationEvidence.fromJson(JsonMapper.builder().build().readTree(json),connection).matches("pfw_old",730,connection);}
    @ParameterizedTest @CsvSource({"0,0","null,730","null,0","0,730"})
    void acceptsOnlyObservedCancellationShapes(String received,String capturable){assertThat(valid(body(received,capturable))).isTrue();}
    @ParameterizedTest @ValueSource(strings={"1","-1","\"0\"","0.0","true","[]","{}","9223372036854775808"})
    void rejectsInvalidReceived(String value){assertThat(valid(body(value,"730"))).isFalse();}
    @ParameterizedTest @ValueSource(strings={"null","1","-1","731","\"730\"","730.0","true","[]","{}","9223372036854775808"})
    void rejectsInvalidCapturable(String value){assertThat(valid(body("null",value))).isFalse();}
    @ParameterizedTest @ValueSource(strings={"id","status","amount","currency","capture_method","livemode","amount_received","amount_capturable"})
    void rejectsMissingFields(String field){var tree=(tools.jackson.databind.node.ObjectNode)JsonMapper.builder().build().readTree(body("null","730"));tree.remove(field);assertThat(valid(tree.toString())).isFalse();}
    @ParameterizedTest @CsvSource({"id,pfw_other","status,requires_capture","status,processing","status,succeeded","status,unknown","currency,usd","capture_method,automatic"})
    void rejectsWrongStrings(String field,String value){var tree=(tools.jackson.databind.node.ObjectNode)JsonMapper.builder().build().readTree(body("null","730"));tree.put(field,value);assertThat(valid(tree.toString())).isFalse();}
    @ParameterizedTest @ValueSource(strings={"\"730\"","730.0","null","true","9223372036854775808","731"})
    void rejectsInvalidAmount(String value){assertThat(valid(body("null","730").replace("\"amount\":730","\"amount\":"+value))).isFalse();}
    @ParameterizedTest @ValueSource(strings={"true","\"false\"","null","0"})
    void rejectsInvalidEnvironment(String value){assertThat(valid(body("null","730").replace("\"livemode\":false","\"livemode\":"+value))).isFalse();}
    @ParameterizedTest @ValueSource(strings={"other","account-other"})
    void rejectsConnectionMismatch(String value){var evidence=OldAuthorizationCancellationEvidence.fromJson(JsonMapper.builder().build().readTree(body("null","730")),connection);assertThat(evidence.matches("pfw_old",730,new CaptureConnectionIdentity(value,"account","TEST","https://api.pay.jp"))).isFalse();}
}
