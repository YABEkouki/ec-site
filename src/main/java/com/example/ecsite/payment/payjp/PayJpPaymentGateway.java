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
import com.example.ecsite.payment.capture.CaptureHttpRequest;
import com.example.ecsite.config.CaptureConnectionProperties;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.MediaType;
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
    private PayJpProperties activeConnection;
    private CaptureConnectionProperties captureConnection;

    @Autowired
    public PayJpPaymentGateway(PayJpProperties properties,
            PayJpAuthorizationMapper authorizationMapper, PayJpSafetyProperties safety, CaptureConnectionProperties captureConnection) {
        this.activeConnection = properties; this.captureConnection = captureConnection;
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

    PayJpPaymentGateway(PayJpProperties properties, PayJpAuthorizationMapper mapper, PayJpSafetyProperties safety) {
        this(properties, mapper, safety, new CaptureConnectionProperties(null, null, null, null));
    }

    // Package-private seam for deterministic response/identity tests.
    PayJpPaymentGateway(RestClient client, PayJpAuthorizationMapper mapper) {
        restClient = client;
        authorizationMapper = mapper;
    }

    private java.time.Clock abortObservationClock=java.time.Clock.systemUTC();
    private java.util.function.LongSupplier abortObservationTicker=System::nanoTime;
    // Mock HTTP and deterministic clocks only; normal transport still disables redirects/retries.
    PayJpPaymentGateway(RestClient client,PayJpAuthorizationMapper mapper,PayJpProperties active,
            CaptureConnectionProperties connection,java.time.Clock clock,java.util.function.LongSupplier ticker){
        this(client,mapper);activeConnection=active;captureConnection=connection;
        abortObservationClock=java.util.Objects.requireNonNull(clock);abortObservationTicker=java.util.Objects.requireNonNull(ticker);
    }
    @Override
    public void sendRequestedAbort(com.example.ecsite.service.orderchange.RequestedAbortDispatchControl.Dispatch dispatch){
        if(dispatch==null)throw new IllegalArgumentException("Dedicated abort dispatch required");
        // This consumes the token even if subsequent connection validation or HTTP fails.
        var saved=dispatch.consumeForGateway();var request=saved.httpRequest();
        if(captureConnection==null||!request.connection().equals(captureConnection.requireIdentity(activeConnection)))
            throw new IllegalStateException("Abort POST connection mismatch");
        try{
            var response=restClient.post().uri(request.path()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key",request.idempotencyKey()).body(request.body()).retrieve().toBodilessEntity();
            if(response.getStatusCode().value()!=200)throw new PaymentGatewayException(PaymentGatewayErrorCategory.OTHER_HTTP_ERROR,
                response.getStatusCode().value(),null,true);
            // Ignore the response body: HTTP 200 is not validated terminal GET evidence.
        }catch(RestClientException failure){throw classify(failure);}
    }

    @Override
    public OldAuthorizationObservation observeOldAuthorization(OldAuthorizationObservation.Expected expected){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Old authorization GET requires no database transaction");
        if(expected==null||captureConnection==null||!expected.connection().equals(captureConnection.requireIdentity(activeConnection)))
            throw new IllegalStateException("Old authorization observation connection mismatch");
        try{return OldAuthorizationObservation.retrieve(expected,restClient,abortObservationClock,abortObservationTicker);}
        catch(RestClientException failure){throw classify(failure);}
        catch(IllegalArgumentException|IllegalStateException failure){
            throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE,null,null,true);
        }
    }

    @Override
    public RequestedAbortObservation observeRequestedAbort(RequestedAbortObservation.Expected expected){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Abort GET requires no database transaction");
        if(expected==null||captureConnection==null||!expected.flow().connection().equals(captureConnection.requireIdentity(activeConnection)))
            throw new IllegalStateException("Abort observation connection mismatch");
        try{return RequestedAbortObservation.retrieve(expected,restClient,abortObservationClock,abortObservationTicker);}
        catch(RestClientException failure){throw classify(failure);}
        catch(IllegalArgumentException|IllegalStateException failure){
            throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE,null,null,true);
        }
    }

    @Override
    public AbortPreparationObservation observeAbortPreparation(com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected expected){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Abort preparation GET requires no database transaction");
        if(expected==null||captureConnection==null||!expected.flow().connection().equals(captureConnection.requireIdentity(activeConnection)))
            throw new IllegalStateException("Abort preparation observation connection mismatch");
        try{return AbortPreparationObservation.retrieve(expected,restClient,abortObservationClock,abortObservationTicker);}
        catch(RestClientException failure){throw classify(failure);}
        catch(IllegalArgumentException|IllegalStateException failure){
            throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE,null,null,true);
        }
    }

    @Override
    public NewFlowClosureObservation observeNewFlowClosure(com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected expected){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Closure GET requires no database transaction");
        if(expected==null||captureConnection==null||!expected.flow().connection().equals(captureConnection.requireIdentity(activeConnection)))
            throw new IllegalStateException("Closure observation connection mismatch");
        try{return NewFlowClosureObservation.retrieve(expected,restClient,abortObservationClock,abortObservationTicker);}
        catch(RestClientException failure){throw classify(failure);}
        catch(IllegalArgumentException|IllegalStateException failure){
            throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE,null,null,true);
        }
    }

    @Override
    public NewFlowClosureObservation.Assessment assessNewFlowClosure(com.example.ecsite.service.orderchange.NewFlowClosureControl.Expected expected){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Closure GET requires no database transaction");
        if(expected==null||captureConnection==null||!expected.flow().connection().equals(captureConnection.requireIdentity(activeConnection)))
            throw new IllegalStateException("Closure observation connection mismatch");
        try{return NewFlowClosureObservation.assess(expected,restClient,abortObservationClock,abortObservationTicker);}
        catch(NewFlowClosureObservation.Expired failure){throw failure;}
        catch(RestClientException failure){throw classify(failure);}
        catch(IllegalArgumentException|IllegalStateException failure){
            throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE,null,null,true);
        }
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
            CaptureHttpRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Capture Gateway requires no database transaction");
        if (request == null || captureConnection == null
                || !request.connection().equals(captureConnection.requireIdentity(activeConnection)))
            throw new IllegalStateException("Capture connection mismatch");

        try {
            PayJpPaymentFlowResponse response = restClient.post()
                    .uri(request.path())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(
                            "Idempotency-Key",
                            request.idempotencyKey())
                    .body(request.body())
                    .retrieve()
                    .body(PayJpPaymentFlowResponse.class);

            if (response == null || response.status() == null
                    || !request.flowId().equals(response.id())) {
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

    /** Observation only. Unknown status and absent optional fields are not normalized. */
    public PayJpPaymentFlowObservation observeOrderChangeFlow(String flowId) {
        if(flowId==null||!flowId.matches("pfw_[A-Za-z0-9_-]{1,251}"))throw new IllegalArgumentException("Invalid Flow identifier");
        try {
            var body=restClient.get().uri("/v2/payment_flows/{id}",flowId).retrieve().body(tools.jackson.databind.JsonNode.class);
            var result=body==null?null:JsonMapper.builder().build().treeToValue(body,PayJpPaymentFlowObservation.class);
            if(result!=null&&"canceled".equals(result.status())&&captureConnection!=null)
                result=result.withOldCancellationEvidence(OldAuthorizationCancellationEvidence.fromJson(body,captureConnection.requireIdentity(activeConnection)));
            if(result==null||!flowId.equals(result.id())||result.status()==null)
                throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE,null,null,true);
            return result;
        } catch(RestClientException failure){throw classify(failure);}
          catch(tools.jackson.core.JacksonException failure){throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE,null,null,true);}
    }

    /** Called only by the disconnected sender after loading a committed request.
     * No automatic retries, reconfirm, failure application or order mutations. */
    public PayJpPaymentFlowObservation sendOrderChangeRequest(
            com.example.ecsite.payment.orderchange.OrderChangeRequestSender.Dispatch dispatch) {
        var saved=dispatch==null?null:dispatch.request();
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Order change Gateway requires no database transaction");
        if(saved==null||captureConnection==null||!saved.httpRequest().connection().equals(captureConnection.requireIdentity(activeConnection)))
            throw new IllegalStateException("Order change connection mismatch");
        var request=saved.httpRequest();
        // Abort before successful authorization remains unconfirmed and is deliberately not dispatched in III-A.
        if(!"CREATE".equals(request.kind())){
            var observed=observeOrderChangeFlow(request.flowId());
            if(!observed.hasUncapturedAuthorization(request.amount())||!"jpy".equals(observed.currency())
                ||!"manual".equals(observed.captureMethod())||observed.livemode()==null
                ||observed.livemode()!=request.connection().mode().equals("LIVE"))
                throw new IllegalStateException("Cancel status or financial evidence is unconfirmed");
            // requires_capture is not evidence that a previous Cancel did not execute.
            // The same stored key/body is used even after a timeout.
        }
        saved.requireReplayAt(java.time.Instant.now());
        try {
            var result=restClient.post().uri(request.path()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key",request.idempotencyKey()).body(request.body())
                .retrieve().body(PayJpPaymentFlowObservation.class);
            if(result==null||result.id()==null||!result.id().matches("pfw_[A-Za-z0-9_-]{1,251}")||result.status()==null
                ||(request.flowId()!=null&&!request.flowId().equals(result.id())))
                throw new PaymentGatewayException(PaymentGatewayErrorCategory.INVALID_RESPONSE,null,null,true);
            return result;
        }catch(RestClientException failure){throw classify(failure);}
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
