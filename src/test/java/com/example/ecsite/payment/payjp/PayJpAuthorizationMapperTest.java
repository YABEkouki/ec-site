package com.example.ecsite.payment.payjp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;

class PayJpAuthorizationMapperTest {

    private final PayJpAuthorizationMapper mapper = new PayJpAuthorizationMapper();

    @Test
    void requiresCaptureMapsToAuthorized() {
        AuthorizationResult result = mapper.map(response("requires_capture", null));

        assertThat(result.status())
                .isEqualTo(AuthorizationResultStatus.AUTHORIZED);
        assertThat(result.failureCode()).isNull();
        assertThat(result.failureMessage()).isNull();
    }

    @Test
    void requiresActionMapsToRequiresAction() {
        AuthorizationResult result = mapper.map(response("requires_action", null));

        assertThat(result.status())
                .isEqualTo(AuthorizationResultStatus.REQUIRES_ACTION);
    }

    @Test
    void requiresConfirmationMapsToRequiresConfirmation() {

        PayJpPaymentFlowResponse response = response(
                "requires_confirmation",
                null);

        AuthorizationResult result = mapper.map(response);

        assertEquals(
                AuthorizationResultStatus.REQUIRES_CONFIRMATION,
                result.status());

        assertNull(result.failureCode());
        assertNull(result.failureMessage());
    }

    @Test
    void processingMapsToPending() {
        AuthorizationResult result = mapper.map(response("processing", null));

        assertThat(result.status())
                .isEqualTo(AuthorizationResultStatus.PENDING);
    }

    @Test
    void requiresPaymentMethodMapsToRequiresPaymentMethodWithError() {

        PayJpPaymentErrorResponse error = new PayJpPaymentErrorResponse(
                "card_declined",
                "カードが拒否されました。");

        PayJpPaymentFlowResponse response = response(
                "requires_payment_method",
                error);

        AuthorizationResult result = mapper.map(response);

        assertEquals(
                AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD,
                result.status());

        assertEquals(
                "card_declined",
                result.failureCode());

        assertEquals(
                "カードが拒否されました。",
                result.failureMessage());
    }

    @Test
    void canceledMapsToFailed() {
        AuthorizationResult result = mapper.map(response("canceled", null));

        assertThat(result.status())
                .isEqualTo(AuthorizationResultStatus.FAILED);
    }

    @Test
    void unexpectedStatusThrowsException() {
        PayJpPaymentFlowResponse response = response("succeeded", null);

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
