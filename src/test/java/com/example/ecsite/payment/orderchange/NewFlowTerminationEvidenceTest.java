package com.example.ecsite.payment.orderchange;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pure tests: no Spring context, database, HTTP client, Gateway or SMTP. */
class NewFlowTerminationEvidenceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID OPERATION = UUID.fromString("7e3d0b0c-f0c6-4ce5-8f90-b3d5900bb6b6");
    private static final CaptureConnectionIdentity CONNECTION =
        new CaptureConnectionIdentity("primary", "merchant", "TEST", "https://api.pay.jp");
    private static final NewFlowTerminationEvidence.Expected EXPECTED =
        new NewFlowTerminationEvidence.Expected(1, OPERATION, "pfw_new", 910, CONNECTION);

    private static ObjectNode response() {
        return (ObjectNode) JSON.readTree("""
            {"id":"pfw_new","status":"canceled","amount":910,"currency":"jpy",
             "capture_method":"manual","livemode":false,"amount_received":0,"amount_capturable":0,
             "metadata":{"order_id":"1","order_change_operation_id":"7e3d0b0c-f0c6-4ce5-8f90-b3d5900bb6b6"}}
            """);
    }

    @Test void acceptsOnlyCompleteMatchingEvidenceAndRetainsNoProviderPayload() {
        var body = response();
        body.put("client_secret", "synthetic-not-a-secret");
        var evidence = NewFlowTerminationEvidence.verify(EXPECTED, CONNECTION, body);
        body.put("id", "pfw_changed_after_validation");
        assertThat(evidence.expected()).isEqualTo(EXPECTED);
        assertThat(evidence.toString()).doesNotContain("synthetic-not-a-secret", "pfw_new", OPERATION.toString());
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
            Arguments.of("id", "\"pfw_old\""),
            Arguments.of("id", "1"),
            Arguments.of("amount", "730"),
            Arguments.of("amount", "\"910\""),
            Arguments.of("amount", "910.0"),
            Arguments.of("amount", "9223372036854775808"),
            Arguments.of("currency", "\"JPY\""),
            Arguments.of("currency", "\" jpy\""),
            Arguments.of("capture_method", "\"automatic\""),
            Arguments.of("livemode", "true"),
            Arguments.of("livemode", "\"false\""),
            Arguments.of("livemode", "0"),
            Arguments.of("status", "\"CANCELED\""),
            Arguments.of("status", "\"\""),
            Arguments.of("status", "0"),
            Arguments.of("metadata", "[]"),
            Arguments.of("metadata", "\"unknown\""));
    }

    @ParameterizedTest @MethodSource("invalidFields")
    void rejectsMismatchedOrMistypedFields(String field, String json) {
        var body = response();
        body.set(field, JSON.readTree(json));
        rejects(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "status", "amount", "currency", "capture_method", "livemode",
        "amount_received", "amount_capturable", "metadata"})
    void rejectsBothMissingAndExplicitNullRequiredFields(String field) {
        var body = response();
        body.remove(field);
        rejects(body);
        body.putNull(field);
        rejects(body);
    }

    static Stream<Arguments> invalidAmounts() {
        return Stream.of("amount_received", "amount_capturable").flatMap(field ->
            Stream.of("1", "-1", "\"0\"", "0.0", "true", "[]", "{}",
                "9223372036854775808", "-9223372036854775809")
                .map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest @MethodSource("invalidAmounts")
    void rejectsNonzeroNonintegralAndOverflowAmounts(String field, String value) {
        var body = response();
        body.set(field, JSON.readTree(value));
        rejects(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"requires_payment_method", "requires_confirmation", "requires_action",
        "processing", "requires_capture", "succeeded", "unknown_future", "timeout", "404"})
    void rejectsEveryNonCanceledStatusEvenWithZeroAmounts(String status) {
        var body = response();
        body.put("status", status);
        rejects(body);
    }

    @ParameterizedTest @ValueSource(strings = {"order_id", "order_change_operation_id"})
    void rejectsMissingNullNonstringAndMismatchedMetadata(String key) {
        for (String value : new String[]{"null", "1", "true", "[]", "{}", "\"other\"", "\" 1\""}) {
            var body = response();
            ((ObjectNode) body.get("metadata")).set(key, JSON.readTree(value));
            rejects(body);
        }
        var body = response();
        ((ObjectNode) body.get("metadata")).remove(key);
        rejects(body);
    }

    @Test void rejectsOldFlowEvenWhenAmountAndMetadataAreOtherwisePlausible() {
        var oldExpected = new NewFlowTerminationEvidence.Expected(1, OPERATION, "pfw_old", 910, CONNECTION);
        assertThatIllegalArgumentException().isThrownBy(() ->
            NewFlowTerminationEvidence.verify(oldExpected, CONNECTION, response()));
    }

    @Test void bindsEvidenceToObservedConnectionIdentity() {
        for (var other : new CaptureConnectionIdentity[]{
            new CaptureConnectionIdentity("other", "merchant", "TEST", "https://api.pay.jp"),
            new CaptureConnectionIdentity("primary", "other", "TEST", "https://api.pay.jp"),
            new CaptureConnectionIdentity("primary", "merchant", "LIVE", "https://api.pay.jp")}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                NewFlowTerminationEvidence.verify(EXPECTED, other, response()));
        }
    }

    @Test void validatesLiveEnvironmentWithoutInferringItFromMissingValues() {
        var live = new CaptureConnectionIdentity("primary", "merchant", "LIVE", "https://api.pay.jp");
        var expected = new NewFlowTerminationEvidence.Expected(1, OPERATION, "pfw_new", 910, live);
        assertThatIllegalArgumentException().isThrownBy(() -> NewFlowTerminationEvidence.verify(expected, live, response()));
        var body = response();
        body.put("livemode", true);
        assertThat(NewFlowTerminationEvidence.verify(expected, live, body).expected()).isEqualTo(expected);
    }

    @Test void rejectsInvalidExpectedContextAndAbsentObservationWithoutEchoingPayload() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NewFlowTerminationEvidence.Expected(0, OPERATION, "pfw_new", 910, CONNECTION));
        assertThatIllegalArgumentException().isThrownBy(() -> new NewFlowTerminationEvidence.Expected(1, null, "pfw_new", 910, CONNECTION));
        assertThatIllegalArgumentException().isThrownBy(() -> new NewFlowTerminationEvidence.Expected(1, OPERATION, null, 910, CONNECTION));
        assertThatIllegalArgumentException().isThrownBy(() -> new NewFlowTerminationEvidence.Expected(1, OPERATION, "pfw_new", 0, CONNECTION));
        assertThatIllegalArgumentException().isThrownBy(() -> new NewFlowTerminationEvidence.Expected(1, OPERATION, "pfw_new", 910, null));
        assertThatIllegalArgumentException().isThrownBy(() -> NewFlowTerminationEvidence.verify(null, CONNECTION, response()));
        assertThatIllegalArgumentException().isThrownBy(() -> NewFlowTerminationEvidence.verify(EXPECTED, null, response()));
        rejects(null);
        rejects(JSON.readTree("[]"));
        var body = response();
        body.put("status", "synthetic-sensitive-value");
        assertThatIllegalArgumentException().isThrownBy(() -> NewFlowTerminationEvidence.verify(EXPECTED, CONNECTION, body))
            .withMessageNotContaining("synthetic-sensitive-value");
    }

    private static void rejects(JsonNode body) {
        assertThatIllegalArgumentException().isThrownBy(() ->
            NewFlowTerminationEvidence.verify(EXPECTED, CONNECTION, body));
    }
}
