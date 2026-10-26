package com.example.ecsite.payment.orderchange;

import java.util.Map;
import java.util.UUID;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;
import com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation;
import tools.jackson.databind.JsonNode;

/**
 * Validated financial closure of the expected new Flow only. This is neither old-authorization
 * evidence nor permission to release stock, change an operation, or send a financial request.
 * No provider payload, client secret or mutable JSON is retained.
 */
public final class NewFlowTerminationEvidence {
    /** Caller must obtain these values from the saved new Payment/operation, not the response. */
    public record Expected(long orderId, UUID operationId, String newFlowId, int amount,
                           CaptureConnectionIdentity connection) {
        public Expected {
            if (orderId <= 0 || operationId == null || connection == null
                    || newFlowId == null || !newFlowId.matches("pfw_[A-Za-z0-9_-]{1,251}")
                    || amount < 50 || amount > 9999999) {
                throw new IllegalArgumentException("Invalid expected new Flow context");
            }
        }

        @Override public String toString() { return "ExpectedNewFlow[redacted]"; }
    }

    private final Expected expected;

    private NewFlowTerminationEvidence(Expected expected) { this.expected = expected; }

    /**
     * Pure validation of an external GET response. The observing connection must be supplied by
     * the trusted transport, never inferred from metadata or an API key. Validate the JSON tree
     * before DTO coercion; a previously mapped DTO cannot prove the original JSON field types.
     * Unrelated provider fields are ignored, consistently with the existing observation DTO.
     */
    public static NewFlowTerminationEvidence verify(Expected expected,
            CaptureConnectionIdentity observingConnection, JsonNode response) {
        if (expected == null || !expected.connection().equals(observingConnection)
                || response == null || !response.isObject()) {
            throw invalid();
        }
        String id = text(response, "id");
        String status = text(response, "status");
        String currency = text(response, "currency");
        String method = text(response, "capture_method");
        JsonNode amount = response.get("amount");
        JsonNode mode = response.get("livemode");
        JsonNode metadata = response.get("metadata");
        if (!expected.newFlowId().equals(id) || !"canceled".equals(status)
                || !"jpy".equals(currency) || !"manual".equals(method)
                || amount == null || !amount.isIntegralNumber() || !amount.canConvertToLong()
                || amount.longValue() != expected.amount()
                || mode == null || !mode.isBoolean()
                || mode.booleanValue() != "LIVE".equals(expected.connection().mode())
                || metadata == null || !metadata.isObject()) {
            throw invalid();
        }
        String orderId = text(metadata, "order_id");
        String operationId = text(metadata, "order_change_operation_id");
        if (!Long.toString(expected.orderId()).equals(orderId)
                || !expected.operationId().toString().equals(operationId)) {
            throw invalid();
        }
        // Reuse the provider DTO's strict integral/Long-range checks. Null or absent amounts
        // remain null, never zero, and are rejected below for terminal evidence.
        var observation = PayJpPaymentFlowObservation.fromJson(id, status, amount.longValue(), currency,
            response.get("amount_received"), response.get("amount_capturable"), method, mode.booleanValue(),
            null, null, Map.of("order_id", orderId, "order_change_operation_id", operationId));
        if (!observation.amountReceivedPresent() || observation.amountReceived() == null
                || observation.amountReceived() != 0L || observation.amountCapturable() == null
                || observation.amountCapturable() != 0L) {
            throw invalid();
        }
        return new NewFlowTerminationEvidence(expected);
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isString()) throw invalid();
        return value.stringValue();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("New Flow termination evidence rejected");
    }

    public Expected expected() { return expected; }

    @Override public String toString() { return "NewFlowTerminationEvidence[redacted]"; }
}
