package com.example.ecsite.payment.payjp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;

class PayJpAuthorizationMapperTest {

    private final PayJpAuthorizationMapper mapper =
            new PayJpAuthorizationMapper();

    @Test
    void requiresCaptureMapsToAuthorized() {
        AuthorizationResult result =
                mapper.map(response("requires_capture", null));

        assertThat(result.status())
                .isEqualTo(AuthorizationResultStatus.AUTHORIZED);
        assertThat(result.failureCode()).isNull();
        assertThat(result.failureMessage()).isNull();
    }

    @Test
    void requiresActionMapsToRequiresAction() {
        AuthorizationResult result =
                mapper.map(response("requires_action", null));

        assertThat(result.status())
                .isEqualTo(AuthorizationResultStatus.REQUIRES_ACTION);
    }

    @Test
    void requiresConfirmationMapsToPending() {
        AuthorizationResult result =
                mapper.map(response("requires_confirmation", null));

        assertThat(result.status())
                .isEqualTo(AuthorizationResultStatus.PENDING);
    }

    @Test
    void processingMapsToPending() {
        AuthorizationResult result =
                mapper.map(response("processing", null));

        assertThat(result.status())
                .isEqualTo(AuthorizationResultStatus.PENDING);
    }

    @Test
    void requiresPaymentMethodMapsToFailedWithError() {
        PayJpPaymentErrorResponse error =
                new PayJpPaymentErrorResponse(
                        "card_declined",
                        "Card was declined");

        AuthorizationResult result =
                mapper.map(response("requires_payment_method", error));

        assertThat(result.status())
                .isEqualTo(AuthorizationResultStatus.FAILED);
        assertThat(result.failureCode())
                .isEqualTo("card_declined");
        assertThat(result.failureMessage())
                .isEqualTo("Card was declined");
    }

    @Test
    void canceledMapsToFailed() {
        AuthorizationResult result =
                mapper.map(response("canceled", null));

        assertThat(result.status())
                .isEqualTo(AuthorizationResultStatus.FAILED);
    }

    @Test
    void unexpectedStatusThrowsException() {
        PayJpPaymentFlowResponse response =
                response("succeeded", null);

        assertThatThrownBy(() -> mapper.map(response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("succeeded");
    }

    private PayJpPaymentFlowResponse response(
            String status,
            PayJpPaymentErrorResponse error) {

        return new PayJpPaymentFlowResponse(
                "pf_test_123",
                "client_secret_test",
                status,
                error);
    }
}
