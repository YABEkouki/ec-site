package com.example.ecsite.payment.payjp;

import org.springframework.stereotype.Component;

import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;

@Component
class PayJpAuthorizationMapper {

    AuthorizationResult map(PayJpPaymentFlowResponse response) {
        return switch (response.status()) {
            case "requires_capture" ->
                result(AuthorizationResultStatus.AUTHORIZED, null);

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
