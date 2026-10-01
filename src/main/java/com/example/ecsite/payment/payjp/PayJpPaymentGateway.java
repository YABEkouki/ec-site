package com.example.ecsite.payment.payjp;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.payment.AuthorizationPreparation;
import com.example.ecsite.payment.AuthorizationRequest;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.PaymentGateway;

@Component
public class PayJpPaymentGateway implements PaymentGateway {

    private final RestClient restClient;

    public PayJpPaymentGateway(PayJpProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + properties.secretKey())
                .build();
    }

    @Override
    public AuthorizationPreparation prepareAuthorization(
            AuthorizationRequest request) {

        Map<String, Object> body = Map.of(
                "amount", request.amount(),
                "currency", "jpy",
                "payment_method_types", List.of("card"),
                "capture_method", "manual");

        PayJpPaymentFlowResponse response = restClient.post()
                .uri("/v2/payment_flows")
                .header("Idempotency-Key", request.idempotencyKey())
                .body(body)
                .retrieve()
                .body(PayJpPaymentFlowResponse.class);

        if (response == null
                || response.id() == null
                || response.clientSecret() == null) {
            throw new IllegalStateException(
                    "PAY.JP returned an invalid Payment Flow response");
        }

        return new AuthorizationPreparation(
                response.id(),
                response.clientSecret());
    }

    @Override
    public AuthorizationResult retrieveAuthorization(
            String providerPaymentId) {

        PayJpPaymentFlowResponse response = restClient.get()
                .uri("/v2/payment_flows/{paymentFlowId}",
                        providerPaymentId)
                .retrieve()
                .body(PayJpPaymentFlowResponse.class);

        if (response == null || response.status() == null) {
            throw new IllegalStateException(
                    "PAY.JP returned an invalid Payment Flow response");
        }

        return mapAuthorizationResult(response);
    }

    private AuthorizationResult mapAuthorizationResult(
            PayJpPaymentFlowResponse response) {

        return switch (response.status()) {
            case "requires_capture" ->
                result(AuthorizationResultStatus.AUTHORIZED, null);

            case "requires_action" ->
                result(AuthorizationResultStatus.REQUIRES_ACTION, null);

            case "requires_payment_method" ->
                result(AuthorizationResultStatus.FAILED,
                        response.lastPaymentError());

            case "requires_confirmation", "processing" ->
                result(AuthorizationResultStatus.PENDING, null);

            case "canceled" ->
                result(AuthorizationResultStatus.FAILED,
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
