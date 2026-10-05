package com.example.ecsite.payment.payjp;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.payment.AuthorizationPreparation;
import com.example.ecsite.payment.AuthorizationRecovery;
import com.example.ecsite.payment.AuthorizationRequest;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.CancellationRequest;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.CaptureRequest;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayException;

@Component
public class PayJpPaymentGateway implements PaymentGateway {

    private final RestClient restClient;
    private final PayJpAuthorizationMapper authorizationMapper;

    public PayJpPaymentGateway(
            PayJpProperties properties,
            PayJpAuthorizationMapper authorizationMapper) {

        this.authorizationMapper = authorizationMapper;

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

        try {
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

        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Failed to create PAY.JP Payment Flow", e);
        }
    }

    @Override
    public PaymentFlowState retrievePaymentFlow(
            String providerPaymentId) {

        try {
            PayJpPaymentFlowResponse response = restClient.get()
                    .uri(
                            "/v2/payment_flows/{paymentFlowId}",
                            providerPaymentId)
                    .retrieve()
                    .body(PayJpPaymentFlowResponse.class);

            if (response == null
                    || response.id() == null
                    || response.status() == null) {
                throw new IllegalStateException(
                        "PAY.JP returned an invalid Payment Flow response");
            }

            PayJpPaymentErrorResponse error = response.lastPaymentError();

            return new PaymentFlowState(
                    response.id(),
                    mapPaymentFlowStatus(response.status()),
                    error != null ? error.code() : null,
                    error != null ? error.message() : null);

        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Failed to retrieve PAY.JP Payment Flow",
                    e);
        }
    }

    @Override
    public AuthorizationRecovery retrieveAuthorization(
            String providerPaymentId) {

        try {
            PayJpPaymentFlowResponse response = restClient.get()
                    .uri("/v2/payment_flows/{paymentFlowId}",
                            providerPaymentId)
                    .retrieve()
                    .body(PayJpPaymentFlowResponse.class);

            if (response == null || response.status() == null) {
                throw new IllegalStateException(
                        "PAY.JP returned an invalid Payment Flow response");
            }

            AuthorizationResult result = authorizationMapper.map(response);

            return new AuthorizationRecovery(
                    result,
                    response.clientSecret());

        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Failed to retrieve PAY.JP Payment Flow", e);
        }

    }

    @Override
    public CancellationResult cancelAuthorization(
            CancellationRequest request) {

        Map<String, Object> body = Map.of(
                "cancellation_reason",
                "requested_by_customer");

        try {
            PayJpPaymentFlowResponse response = restClient.post()
                    .uri(
                            "/v2/payment_flows/{paymentFlowId}/cancel",
                            request.providerPaymentId())
                    .header(
                            "Idempotency-Key",
                            request.idempotencyKey())
                    .body(body)
                    .retrieve()
                    .body(PayJpPaymentFlowResponse.class);

            if (response == null || response.status() == null) {
                throw new IllegalStateException(
                        "PAY.JP returned an invalid Payment Flow response");
            }

            if ("canceled".equals(response.status())) {
                return new CancellationResult(
                        CancellationResultStatus.CANCELLED,
                        response.id());
            }

            return new CancellationResult(
                    CancellationResultStatus.PENDING,
                    response.id());

        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Failed to cancel PAY.JP Payment Flow",
                    e);
        }
    }

    @Override
    public CaptureResult capture(
            CaptureRequest request) {

        Map<String, Object> body = Map.of(
                "amount_to_capture", request.amount());

        try {
            PayJpPaymentFlowResponse response = restClient.post()
                    .uri(
                            "/v2/payment_flows/{paymentFlowId}/capture",
                            request.providerPaymentId())
                    .header(
                            "Idempotency-Key",
                            request.idempotencyKey())
                    .body(body)
                    .retrieve()
                    .body(PayJpPaymentFlowResponse.class);

            if (response == null || response.status() == null) {
                throw new IllegalStateException(
                        "PAY.JP returned an invalid Payment Flow response");
            }

            if ("succeeded".equals(response.status())) {
                return new CaptureResult(
                        CaptureResultStatus.CAPTURED,
                        response.id(),
                        null,
                        null);
            }

            return new CaptureResult(
                    CaptureResultStatus.PENDING,
                    response.id(),
                    null,
                    null);

        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Failed to capture PAY.JP Payment Flow",
                    e);
        }
    }

    private PaymentFlowStatus mapPaymentFlowStatus(String status) {

        return switch (status) {
            case "requires_payment_method" ->
                PaymentFlowStatus.REQUIRES_PAYMENT_METHOD;
            case "requires_confirmation" ->
                PaymentFlowStatus.REQUIRES_CONFIRMATION;
            case "requires_action" ->
                PaymentFlowStatus.REQUIRES_ACTION;
            case "processing" ->
                PaymentFlowStatus.PROCESSING;
            case "requires_capture" ->
                PaymentFlowStatus.REQUIRES_CAPTURE;
            case "succeeded" ->
                PaymentFlowStatus.SUCCEEDED;
            case "canceled" ->
                PaymentFlowStatus.CANCELED;
            default ->
                throw new IllegalStateException(
                        "Unknown PAY.JP Payment Flow status: " + status);
        };
    }

}
