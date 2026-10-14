package com.example.ecsite.payment.payjp;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import com.example.ecsite.config.PayJpSafetyProperties;
import com.example.ecsite.payment.PaymentGatewayErrorCategory;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PreDestroy;
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

    private CloseableHttpClient httpClient;

    @Autowired
    public PayJpPaymentGateway(PayJpProperties properties,
            PayJpAuthorizationMapper authorizationMapper, PayJpSafetyProperties safety) {
        if (!safety.isValid()) throw new IllegalArgumentException("Invalid PAY.JP safety configuration");
        this.authorizationMapper = authorizationMapper;
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(safety.connectTimeout().toMillis()))
                        .setSocketTimeout(Timeout.ofMilliseconds(safety.readTimeout().toMillis())).build()).build();
        httpClient = HttpClients.custom().setConnectionManager(connections)
                .disableAutomaticRetries().disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(safety.readTimeout().toMillis())).build()).build();
        restClient = RestClient.builder().baseUrl(properties.apiBaseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.secretKey()).build();
    }

    // Package-private seam for deterministic response/identity tests.
    PayJpPaymentGateway(RestClient client, PayJpAuthorizationMapper mapper) {
        restClient = client;
        authorizationMapper = mapper;
    }

    @PreDestroy
    public void close() throws java.io.IOException {
        if (httpClient != null) httpClient.close();
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
                    || response.id() == null || response.id().isBlank()
                    || response.clientSecret() == null || response.clientSecret().isBlank()) {
                throw new IllegalStateException(
                        "PAY.JP returned an invalid Payment Flow response");
            }

            return new AuthorizationPreparation(
                    response.id(),
                    response.clientSecret());

        } catch (RestClientException e) {
            throw classify(e);
        } catch (IllegalStateException e) {
            throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE, null, null, true);
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
                    || !providerPaymentId.equals(response.id())
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
            throw classify(e);
        } catch (IllegalStateException e) {
            throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE, null, null, true);
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

            if (response == null || response.status() == null
                    || !providerPaymentId.equals(response.id())) {
                throw new IllegalStateException(
                        "PAY.JP returned an invalid Payment Flow response");
            }

            AuthorizationResult result = authorizationMapper.map(response);

            return new AuthorizationRecovery(
                    result,
                    response.clientSecret());

        } catch (RestClientException e) {
            throw classify(e);
        } catch (IllegalStateException e) {
            throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE, null, null, true);
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

            if (response == null || response.status() == null
                    || !request.providerPaymentId().equals(response.id())) {
                throw new IllegalStateException(
                        "PAY.JP returned an invalid Payment Flow response");
            }

            mapPaymentFlowStatus(response.status());
            if ("canceled".equals(response.status())) {
                return new CancellationResult(
                        CancellationResultStatus.CANCELLED,
                        response.id());
            }

            return new CancellationResult(
                    CancellationResultStatus.PENDING,
                    response.id());

        } catch (RestClientException e) {
            throw classify(e);
        } catch (IllegalStateException e) {
            throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE, null, null, true);
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

            if (response == null || response.status() == null
                    || !request.providerPaymentId().equals(response.id())) {
                throw new IllegalStateException(
                        "PAY.JP returned an invalid Payment Flow response");
            }

            mapPaymentFlowStatus(response.status());
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
            throw classify(e);
        } catch (IllegalStateException e) {
            throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE, null, null, true);
        }
    }

    private PaymentGatewayException classify(RestClientException failure) {
        if (failure instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            String code = null;
            try {
                var value = JsonMapper.builder().build().readTree(response.getResponseBodyAsByteArray()).path("code");
                if (value.isString() && value.asString().matches("[a-z][a-z0-9_]{0,79}")) code = value.asString();
            } catch (RuntimeException ignored) { /* Never retain or log raw bodies. */ }
            PaymentGatewayErrorCategory category = status >= 500
                    ? PaymentGatewayErrorCategory.SERVER_RESULT_UNKNOWN
                    : status == 409 && "idempotency_conflict".equals(code)
                            ? PaymentGatewayErrorCategory.IDEMPOTENCY_PARAMETER_CONFLICT
                    : status == 409 && "idempotency_timeout".equals(code)
                            ? PaymentGatewayErrorCategory.IDEMPOTENCY_WAIT_TIMEOUT
                            : PaymentGatewayErrorCategory.OTHER_HTTP_ERROR;
            // HTTP errors do not establish the persisted payment's final state.
            return new PaymentGatewayException(category, status, code, true);
        }
        return new PaymentGatewayException(failure instanceof ResourceAccessException
                ? PaymentGatewayErrorCategory.TRANSPORT_RESULT_UNKNOWN
                : PaymentGatewayErrorCategory.INVALID_RESPONSE, null, null, true);
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
