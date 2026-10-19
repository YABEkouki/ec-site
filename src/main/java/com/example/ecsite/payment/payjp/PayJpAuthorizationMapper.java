package com.example.ecsite.payment.payjp;

import org.springframework.stereotype.Component;

import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;

@Component
class PayJpAuthorizationMapper {

    AuthorizationResult map(PayJpPaymentFlowResponse response) {
        return switch (response.status()) {
            case "requires_capture" ->
                authorized(response.id());

            case "requires_action" ->
                result(AuthorizationResultStatus.REQUIRES_ACTION, null);

            case "requires_payment_method" ->
                result(
                        AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD,
                        response.lastPaymentError());

            case "requires_confirmation" ->
                result(
                        AuthorizationResultStatus.REQUIRES_CONFIRMATION,
                        null);

            case "processing" ->
                result(AuthorizationResultStatus.PENDING, null);

            case "canceled" ->
                result(
                        AuthorizationResultStatus.FAILED,
                        response.lastPaymentError());

            default ->
                throw new IllegalStateException(
                        "Unexpected PAY.JP Payment Flow status: "
                                + response.status());
        };
    }

    private AuthorizationResult authorized(String flowId) {
        // The Gateway verifies this response ID against the requested Payment Flow before mapping.
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalStateException("Authorized Payment Flow ID is required");
        }
        return new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED, flowId, null, null);
    }

    private AuthorizationResult result(
            AuthorizationResultStatus status,
            PayJpPaymentErrorResponse error) {

        return new AuthorizationResult(
                status,
                null,
                error != null ? error.code() : null,
                error != null ? error.message() : null);
    }
}
