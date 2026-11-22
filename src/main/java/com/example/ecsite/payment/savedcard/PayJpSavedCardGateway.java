package com.example.ecsite.payment.savedcard;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.config.PayJpSafetyProperties;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Feature139: PAY.JP v2 customers, PaymentMethods and Setup Flows (field names from docs.pay.jp/v2/openapi.json). */
@Component
public class PayJpSavedCardGateway implements SavedCardGateway {

    private static final String CUSTOMER_ID = "[A-Za-z0-9_-]{1,100}";
    private static final String PAYMENT_METHOD_ID = "pm_[A-Za-z0-9_-]{1,252}";
    private static final String SETUP_FLOW_ID = "[A-Za-z0-9_-]{1,255}";
    private static final String KEY = "[A-Za-z0-9_-]{1,100}";
    private static final int MAX_PAGES = 10;

    private final RestClient restClient;
    private CloseableHttpClient httpClient;

    @Autowired
    public PayJpSavedCardGateway(PayJpProperties properties, PayJpSafetyProperties safety) {
        if (!safety.isValid()) throw new IllegalArgumentException("Invalid PAY.JP safety configuration");
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

    /** Package-private seam for mock HTTP tests. */
    PayJpSavedCardGateway(RestClient restClient) {
        this.restClient = restClient;
    }

    @PreDestroy
    public void close() throws java.io.IOException {
        if (httpClient != null) httpClient.close();
    }

    @Override
    public Optional<Customer> findCustomer(String customerId) {
        require(customerId, CUSTOMER_ID);
        return get("/v2/customers/{id}", customerId).map(body -> customer(body, customerId));
    }

    @Override
    public Customer createCustomer(String customerId, long userId, String idempotencyKey) {
        require(customerId, CUSTOMER_ID);
        var body = new LinkedHashMap<String, Object>();
        body.put("id", customerId);
        body.put("metadata", Map.of("user_id", Long.toString(userId)));
        return customer(post("/v2/customers", body, idempotencyKey), customerId);
    }

    @Override
    public List<Card> listCards(String customerId) {
        require(customerId, CUSTOMER_ID);
        var cards = new ArrayList<Card>();
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body = after == null
                    ? get("/v2/customers/{id}/payment_methods?limit=100", customerId).orElseThrow(SavedCardGatewayPayJpSupport::notFound)
                    : get("/v2/customers/{id}/payment_methods?limit=100&starting_after={after}", customerId, after)
                            .orElseThrow(SavedCardGatewayPayJpSupport::notFound);
            JsonNode data = body.path("data");
            if (!data.isArray()) throw invalid();
            for (JsonNode item : data) {
                Card card = card(item);
                if (card.attachedTo(customerId)) cards.add(card);
                after = card.id();
            }
            if (!body.path("has_more").asBoolean(false) || data.isEmpty()) return cards;
        }
        throw invalid();
    }

    @Override
    public Optional<Card> findCard(String paymentMethodId) {
        require(paymentMethodId, PAYMENT_METHOD_ID);
        return get("/v2/payment_methods/{id}", paymentMethodId).map(body -> {
            Card card = card(body);
            if (!paymentMethodId.equals(card.id())) throw invalid();
            return card;
        });
    }

    @Override
    public SetupStart createSetupFlow(String customerId, long userId, long operationId, String idempotencyKey) {
        require(customerId, CUSTOMER_ID);
        var body = new LinkedHashMap<String, Object>();
        body.put("customer_id", customerId);
        body.put("payment_method_types", List.of("card"));
        body.put("metadata", Map.of("user_id", Long.toString(userId), "saved_card_operation_id", Long.toString(operationId)));
        JsonNode response = post("/v2/setup_flows", body, idempotencyKey);
        String id = text(response, "id");
        String secret = text(response, "client_secret");
        if (id == null || !id.matches(SETUP_FLOW_ID) || secret == null || secret.isBlank()
                || !customerId.equals(text(response, "customer_id"))) throw invalid();
        return new SetupStart(id, secret);
    }

    @Override
    public Optional<SetupState> findSetupFlow(String setupFlowId) {
        require(setupFlowId, SETUP_FLOW_ID);
        return get("/v2/setup_flows/{id}", setupFlowId).map(body -> {
            SetupState state = setupState(body);
            if (!setupFlowId.equals(state.id())) throw invalid();
            return state;
        });
    }

    @Override
    public List<SetupState> findSetupFlowsByOperation(String customerId, long operationId) {
        require(customerId, CUSTOMER_ID);
        var found = new ArrayList<SetupState>();
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body = after == null
                    ? get("/v2/setup_flows?customer_id={c}&limit=100", customerId).orElseThrow(SavedCardGatewayPayJpSupport::notFound)
                    : get("/v2/setup_flows?customer_id={c}&limit=100&starting_after={a}", customerId, after)
                            .orElseThrow(SavedCardGatewayPayJpSupport::notFound);
            JsonNode data = body.path("data");
            if (!data.isArray()) throw invalid();
            for (JsonNode item : data) {
                SetupState state = setupState(item);
                after = state.id();
                if (customerId.equals(state.customerId())
                        && Long.toString(operationId).equals(text(item.path("metadata"), "saved_card_operation_id")))
                    found.add(state);
            }
            if (!body.path("has_more").asBoolean(false) || data.isEmpty()) return found;
        }
        throw invalid();
    }

    @Override
    public Card detach(String paymentMethodId, String idempotencyKey) {
        require(paymentMethodId, PAYMENT_METHOD_ID);
        Card card = card(post("/v2/payment_methods/" + paymentMethodId + "/detach", null, idempotencyKey));
        if (!paymentMethodId.equals(card.id())) throw invalid();
        return card;
    }

    @Override
    public Customer setDefaultCard(String customerId, String paymentMethodId, String idempotencyKey) {
        require(customerId, CUSTOMER_ID);
        require(paymentMethodId, PAYMENT_METHOD_ID);
        return customer(post("/v2/customers/" + customerId, Map.of("default_payment_method_id", paymentMethodId),
                idempotencyKey), customerId);
    }

    private Optional<JsonNode> get(String uri, Object... variables) {
        try {
            JsonNode body = restClient.get().uri(uri, variables).retrieve().body(JsonNode.class);
            if (body == null) throw invalid();
            return Optional.of(body);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) return Optional.empty();
            throw SavedCardGatewayPayJpSupport.classify(failure);
        } catch (RestClientException failure) {
            throw SavedCardGatewayPayJpSupport.classify(failure);
        } catch (tools.jackson.core.JacksonException failure) {
            throw invalid();
        }
    }

    private JsonNode post(String uri, Object body, String idempotencyKey) {
        require(idempotencyKey, KEY);
        try {
            var request = restClient.post().uri(uri).header("Idempotency-Key", idempotencyKey);
            JsonNode response = (body == null ? request : request.body(body)).retrieve().body(JsonNode.class);
            if (response == null) throw invalid();
            return response;
        } catch (RestClientException failure) {
            throw SavedCardGatewayPayJpSupport.classify(failure);
        } catch (tools.jackson.core.JacksonException failure) {
            throw invalid();
        }
    }

    private static Customer customer(JsonNode body, String expectedId) {
        String id = text(body, "id");
        if (!expectedId.equals(id)) throw invalid();
        String defaultCard = text(body, "default_payment_method_id");
        if (defaultCard != null && !defaultCard.matches(PAYMENT_METHOD_ID)) throw invalid();
        return new Customer(id, defaultCard);
    }

    private static Card card(JsonNode body) {
        String id = text(body, "id");
        JsonNode card = body.path("card");
        if (id == null || !id.matches(PAYMENT_METHOD_ID) || !"card".equals(text(body, "type")) || !card.isObject())
            throw invalid();
        int month = card.path("exp_month").asInt(0);
        int year = card.path("exp_year").asInt(0);
        String last4 = text(card, "last4");
        String brand = text(card, "brand");
        if (month < 1 || month > 12 || year < 2000 || year > 9999 || last4 == null || !last4.matches("[0-9]{4}")
                || brand == null || brand.isBlank()) throw invalid();
        return new Card(id, text(body, "customer_id"), brand, last4, month, year, text(card, "fingerprint"),
                time(body, "created_at"), time(body, "detached_at"));
    }

    private static SetupState setupState(JsonNode body) {
        String id = text(body, "id");
        String status = text(body, "status");
        String paymentMethod = text(body, "payment_method_id");
        if (id == null || !id.matches(SETUP_FLOW_ID) || status == null
                || (paymentMethod != null && !paymentMethod.matches(PAYMENT_METHOD_ID))) throw invalid();
        return new SetupState(id, text(body, "customer_id"), status, paymentMethod);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) throw invalid();
        return value.asString();
    }

    private static OffsetDateTime time(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException failure) {
            throw invalid();
        }
    }

    private static void require(String value, String pattern) {
        if (value == null || !value.matches(pattern)) throw new IllegalArgumentException("Invalid PAY.JP identifier");
    }

    private static SavedCardGatewayException invalid() {
        return new SavedCardGatewayException(SavedCardGatewayException.Kind.UNKNOWN, null, "invalid_response");
    }

    /** Error classification shared by the gateway (kept separate so tests can exercise it directly). */
    static final class SavedCardGatewayPayJpSupport {
        private SavedCardGatewayPayJpSupport() {}

        static SavedCardGatewayException notFound() {
            return new SavedCardGatewayException(SavedCardGatewayException.Kind.REJECTED, 404, "not_found");
        }

        static SavedCardGatewayException classify(RestClientException failure) {
            if (failure instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                String code = null;
                try {
                    var value = JsonMapper.builder().build().readTree(response.getResponseBodyAsByteArray()).path("code");
                    if (value.isString() && value.asString().matches("[a-z][a-z0-9_]{0,79}")) code = value.asString();
                } catch (RuntimeException ignored) { /* Never retain or log raw bodies. */ }
                boolean unknown = status >= 500
                        || (status == 409 && code != null && code.startsWith("idempotency_"));
                return new SavedCardGatewayException(unknown ? SavedCardGatewayException.Kind.UNKNOWN
                        : SavedCardGatewayException.Kind.REJECTED, status, code);
            }
            return new SavedCardGatewayException(SavedCardGatewayException.Kind.UNKNOWN, null, "transport");
        }
    }
}
