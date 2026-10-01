package com.example.ecsite.payment.payjp;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.payment.AuthorizationPreparation;
import com.example.ecsite.payment.AuthorizationRequest;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.CancellationRequest;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
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
    public AuthorizationResult retrieveAuthorization(
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

            return authorizationMapper.map(response);
        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Failed to create PAY.JP Payment Flow", e);
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

}
