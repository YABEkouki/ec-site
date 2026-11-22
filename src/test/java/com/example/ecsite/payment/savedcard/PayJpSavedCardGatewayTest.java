package com.example.ecsite.payment.savedcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Feature139-A: request field names (docs.pay.jp/v2/openapi.json) and response/error handling. */
class PayJpSavedCardGatewayTest {

    MockRestServiceServer server;
    PayJpSavedCardGateway gateway;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("https://api.pay.jp");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new PayJpSavedCardGateway(builder.build());
    }

    private void next() {
        server.verify();
        server.reset();
    }

    static String card(String id, String customer, String detachedAt, String created) {
        return "{\"object\":\"payment_method\",\"id\":\"" + id + "\",\"type\":\"card\",\"customer_id\":"
                + (customer == null ? "null" : "\"" + customer + "\"") + ",\"detached_at\":"
                + (detachedAt == null ? "null" : "\"" + detachedAt + "\"") + ",\"created_at\":\"" + created + "\","
                + "\"card\":{\"brand\":\"Visa\",\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2030,\"fingerprint\":\"fp1\"}}";
    }

    @Test
    void createsCustomerWithChosenIdAndNoPersonalData() {
        server.expect(requestTo("https://api.pay.jp/v2/customers")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "f139-k1"))
                .andExpect(content().json("{\"id\":\"ecu-t-7\",\"metadata\":{\"user_id\":\"7\"}}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"id\":\"ecu-t-7\",\"default_payment_method_id\":null}", MediaType.APPLICATION_JSON));
        assertThat(gateway.createCustomer("ecu-t-7", 7, "f139-k1")).isEqualTo(new SavedCardGateway.Customer("ecu-t-7", null));
        server.verify();
    }

    @Test
    void missingCustomerIsEmptyAndOtherErrorsAreClassified() {
        server.expect(requestTo("https://api.pay.jp/v2/customers/ecu-t-7")).andRespond(withResourceNotFound());
        assertThat(gateway.findCustomer("ecu-t-7")).isEmpty();
        next();

        server.expect(requestTo("https://api.pay.jp/v2/customers/ecu-t-8"))
                .andRespond(withServerError().body("{\"code\":\"server_error\"}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.findCustomer("ecu-t-8")).isInstanceOfSatisfying(SavedCardGatewayException.class,
                e -> assertThat(e.kind()).isEqualTo(SavedCardGatewayException.Kind.UNKNOWN));
        next();

        server.expect(requestTo("https://api.pay.jp/v2/customers")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("{\"code\":\"already_exists_id\",\"detail\":\"secret-free\"}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.createCustomer("ecu-t-9", 9, "f139-k2")).isInstanceOfSatisfying(SavedCardGatewayException.class,
                e -> {
                    assertThat(e.kind()).isEqualTo(SavedCardGatewayException.Kind.REJECTED);
                    assertThat(e.code()).isEqualTo("already_exists_id");
                    assertThat(e.getMessage()).doesNotContain("secret-free");
                });
        next();

        server.expect(requestTo("https://api.pay.jp/v2/payment_methods/pm_1/detach")).andRespond(withStatus(HttpStatus.CONFLICT)
                .body("{\"code\":\"idempotency_conflict\"}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.detach("pm_1", "f139-k3")).isInstanceOfSatisfying(SavedCardGatewayException.class,
                e -> assertThat(e.kind()).isEqualTo(SavedCardGatewayException.Kind.UNKNOWN));
        server.verify();
    }

    @Test
    void listsOnlyAttachedCardsOfTheCustomerAcrossPages() {
        server.expect(requestTo("https://api.pay.jp/v2/customers/ecu-t-7/payment_methods?limit=100"))
                .andRespond(withSuccess("{\"data\":[" + card("pm_a", "ecu-t-7", null, "2026-11-22T01:00:00Z") + ","
                        + card("pm_b", "ecu-t-7", "2026-11-22T02:00:00Z", "2026-11-22T01:30:00Z") + "],\"has_more\":true}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.pay.jp/v2/customers/ecu-t-7/payment_methods?limit=100&starting_after=pm_b"))
                .andRespond(withSuccess("{\"data\":[" + card("pm_c", "other", null, "2026-11-22T03:00:00Z") + ","
                        + card("pm_d", "ecu-t-7", null, "2026-11-22T04:00:00Z") + "],\"has_more\":false}", MediaType.APPLICATION_JSON));
        var cards = gateway.listCards("ecu-t-7");
        assertThat(cards).extracting(SavedCardGateway.Card::id).containsExactly("pm_a", "pm_d");
        assertThat(cards.getFirst().brand()).isEqualTo("Visa");
        assertThat(cards.getFirst().last4()).isEqualTo("4242");
        assertThat(cards.getFirst().expMonth()).isEqualTo(12);
        assertThat(cards.getFirst().expYear()).isEqualTo(2030);
        assertThat(cards.getFirst().fingerprint()).isEqualTo("fp1");
        assertThat(cards.getFirst().toString()).doesNotContain("4242").doesNotContain("fp1");
        server.verify();
    }

    @Test
    void setupFlowUsesCustomerIdAndRedactsTheSecret() {
        server.expect(requestTo("https://api.pay.jp/v2/setup_flows")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "f139-k4"))
                .andExpect(content().json("{\"customer_id\":\"ecu-t-7\",\"payment_method_types\":[\"card\"],"
                        + "\"metadata\":{\"user_id\":\"7\",\"saved_card_operation_id\":\"11\"}}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"id\":\"sfw_1\",\"client_secret\":\"sfw_1_secret_x\",\"customer_id\":\"ecu-t-7\","
                        + "\"status\":\"requires_payment_method\"}", MediaType.APPLICATION_JSON));
        var start = gateway.createSetupFlow("ecu-t-7", 7, 11, "f139-k4");
        assertThat(start.id()).isEqualTo("sfw_1");
        assertThat(start.clientSecret()).isEqualTo("sfw_1_secret_x");
        assertThat(start.toString()).doesNotContain("secret_x");
        next();

        server.expect(requestTo("https://api.pay.jp/v2/setup_flows?customer_id=ecu-t-7&limit=100"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"sfw_1\",\"customer_id\":\"ecu-t-7\",\"status\":\"succeeded\","
                        + "\"payment_method_id\":\"pm_a\",\"metadata\":{\"saved_card_operation_id\":\"11\"}},"
                        + "{\"id\":\"sfw_2\",\"customer_id\":\"ecu-t-7\",\"status\":\"requires_payment_method\","
                        + "\"payment_method_id\":null,\"metadata\":{\"saved_card_operation_id\":\"12\"}}],\"has_more\":false}",
                        MediaType.APPLICATION_JSON));
        assertThat(gateway.findSetupFlowsByOperation("ecu-t-7", 11)).extracting(SavedCardGateway.SetupState::id).containsExactly("sfw_1");
        server.verify();
    }

    @Test
    void defaultCardAndDetachUseDocumentedFields() {
        server.expect(requestTo("https://api.pay.jp/v2/customers/ecu-t-7")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "f139-k5"))
                .andExpect(content().json("{\"default_payment_method_id\":\"pm_a\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"id\":\"ecu-t-7\",\"default_payment_method_id\":\"pm_a\"}", MediaType.APPLICATION_JSON));
        assertThat(gateway.setDefaultCard("ecu-t-7", "pm_a", "f139-k5").defaultPaymentMethodId()).isEqualTo("pm_a");
        next();

        server.expect(requestTo("https://api.pay.jp/v2/payment_methods/pm_a/detach")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "f139-k6"))
                .andRespond(withSuccess(card("pm_a", "ecu-t-7", "2026-11-22T05:00:00Z", "2026-11-22T01:00:00Z"), MediaType.APPLICATION_JSON));
        assertThat(gateway.detach("pm_a", "f139-k6").detachedAt()).isNotNull();
        server.verify();
    }

    @Test
    void rejectsMalformedIdentifiersBeforeSending() {
        assertThatThrownBy(() -> gateway.findCard("../customers")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.detach("pm_1/x", "f139-k7")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.findCustomer("a b")).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void checkoutFlowUpdateConfirmAndAttachUseDocumentedFields() {
        String flow = "{\"id\":\"pfw_1\",\"status\":\"requires_confirmation\",\"customer_id\":\"ecu-t-7\","
                + "\"payment_method_id\":\"pm_a\",\"client_secret\":\"pfw_1_secret_x\"}";
        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_1")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "f139b-k1"))
                .andExpect(content().json("{\"customer_id\":\"ecu-t-7\",\"payment_method_id\":\"pm_a\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess(flow, MediaType.APPLICATION_JSON));
        var updated = gateway.updateFlowCustomer("pfw_1", "ecu-t-7", "pm_a", "f139b-k1");
        assertThat(updated).isEqualTo(new SavedCardGateway.FlowState("pfw_1", "requires_confirmation", "ecu-t-7", "pm_a"));
        assertThat(updated.toString()).doesNotContain("secret");
        next();

        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_1")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"customer_id\":\"ecu-t-7\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"id\":\"pfw_1\",\"status\":\"requires_payment_method\",\"customer_id\":\"ecu-t-7\","
                        + "\"payment_method_id\":null}", MediaType.APPLICATION_JSON));
        assertThat(gateway.updateFlowCustomer("pfw_1", "ecu-t-7", null, "f139b-k2").paymentMethodId()).isNull();
        next();

        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_1/confirm")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "f139b-k3"))
                .andExpect(content().json("{\"return_url\":\"http://localhost:8080/checkout/payment/return\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body("{\"code\":\"card_declined\"}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.confirmFlow("pfw_1", "http://localhost:8080/checkout/payment/return", "f139b-k3"))
                .isInstanceOfSatisfying(SavedCardGatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(SavedCardGatewayException.Kind.REJECTED);
                    assertThat(e.code()).isEqualTo("card_declined");
                });
        next();

        server.expect(requestTo("https://api.pay.jp/v2/payment_methods/pm_a/attach")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "f139b-k4"))
                .andExpect(content().json("{\"customer_id\":\"ecu-t-7\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess(card("pm_a", "ecu-t-7", null, "2026-11-22T01:00:00Z"), MediaType.APPLICATION_JSON));
        assertThat(gateway.attach("pm_a", "ecu-t-7", "f139b-k4").customerId()).isEqualTo("ecu-t-7");
        next();

        server.expect(requestTo("https://api.pay.jp/v2/payment_flows/pfw_2")).andRespond(withResourceNotFound());
        assertThat(gateway.findFlow("pfw_2")).isEmpty();
        assertThatThrownBy(() -> gateway.confirmFlow("pfw_1", "javascript:alert(1)", "f139b-k5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.updateFlowCustomer("pfw_1/../x", "ecu-t-7", null, "f139b-k6")).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
}
