package com.example.ecsite.service.savedcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.ecsite.config.PayJpCustomerProperties;
import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.config.PayJpSafetyProperties;
import com.example.ecsite.payment.savedcard.SavedCardGateway;
import com.example.ecsite.payment.savedcard.SavedCardGatewayException;
import com.example.ecsite.service.savedcard.SavedCardOperationStore.Reason;
import com.example.ecsite.service.savedcard.SavedCardOperationStore.Status;
import com.example.ecsite.service.savedcard.SavedCardOperationStore.Type;
import com.example.ecsite.support.isolation.IsolatedTestMode;

/** Feature139-A: V105 guard, operation records and the saved card service against an in-memory PAY.JP. */
@SpringBootTest(properties = { "logging.level.root=WARN", "spring.jpa.show-sql=false" })
@IsolatedTestMode(IsolatedTestMode.Mode.SHARED)
class SavedCardPostgresTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired SavedCardOperationStore store;

    FakePayJp payjp;
    SavedCardService service;
    long user;
    String customer;

    @BeforeEach
    void setUp() {
        user = jdbc.queryForObject("insert into users(username,password,created_at,updated_at) values (?,'unused',"
                + "localtimestamp,localtimestamp) returning id", Long.class, "f139a-" + UUID.randomUUID());
        String prefix = "t139-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        customer = prefix + user;
        payjp = new FakePayJp();
        service = new SavedCardService(new PayJpCustomerProperties(prefix),
                new PayJpProperties("pk_test_fixture", "sk_test_fixture", "http://127.0.0.1:1", "token"), payjp, store,
                new PayJpSafetyProperties(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofHours(23)));
    }

    // ---- V105 guard -------------------------------------------------------------------------------------------

    @Test
    void operationsStartPendingAndMoveOnlyForward() {
        var op = store.begin(user, Type.DETACH, Reason.USER, customer, "pm_x1");
        assertThat(op.status()).isEqualTo(Status.PENDING);
        assertThat(op.idempotencyKey()).startsWith("f139-detach-");
        assertThat(op.createdAt()).isNotNull();
        assertThat(op.completedAt()).isNull();

        assertThat(store.finish(op.id(), Status.PENDING, Status.UNCONFIRMED, "transport", null)).isPresent();
        assertThat(store.finish(op.id(), Status.PENDING, Status.SUCCEEDED, null, null)).isEmpty();
        var done = store.finish(op.id(), Status.UNCONFIRMED, Status.SUCCEEDED, "ignored", null).orElseThrow();
        assertThat(done.completedAt()).isNotNull();
        assertThat(done.errorCode()).isNull();

        assertThatThrownBy(() -> jdbc.update("update saved_card_operations set status='FAILED' where id=?", op.id()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("transition");
        assertThatThrownBy(() -> jdbc.update("update saved_card_operations set provider_payment_method_id='pm_y' where id=?", op.id()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("redefined");
        assertThatThrownBy(() -> jdbc.update("delete from saved_card_operations where id=?", op.id()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("removed");
    }

    @Test
    void insertsMustBePendingAndCompletionsNeedTheirStart() {
        assertThatThrownBy(() -> jdbc.update("insert into saved_card_operations(user_id,operation_type,reason,provider_customer_id,"
                + "provider_payment_method_id,idempotency_key,status,created_at) values (?,'DETACH','USER',?,'pm_a','k-direct','SUCCEEDED',"
                + "localtimestamp)", user, customer)).isInstanceOf(DataAccessException.class).hasMessageContaining("pending");
        assertThatThrownBy(() -> store.recordSetupCompletion(user, customer, "sfw_unknown", "pm_a"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("successful start");
        // Reasons are tied to the operation type.
        assertThatThrownBy(() -> store.begin(user, Type.SET_DEFAULT, Reason.DUPLICATE, customer, "pm_a"))
                .isInstanceOf(DataAccessException.class);
        // The Setup Flow id is learned only when the start succeeds.
        var start = store.begin(user, Type.SETUP_START, Reason.USER, customer, null);
        assertThatThrownBy(() -> store.finish(start.id(), Status.PENDING, Status.UNCONFIRMED, null, "sfw_early"))
                .isInstanceOf(DataAccessException.class);
        store.finish(start.id(), Status.PENDING, Status.SUCCEEDED, null, "sfw_" + start.id());
        var first = store.recordSetupCompletion(user, customer, "sfw_" + start.id(), "pm_a");
        assertThat(store.recordSetupCompletion(user, customer, "sfw_" + start.id(), "pm_a").id()).isEqualTo(first.id());
    }

    @Test
    void oneOperationInFlightPerUser() {
        var op = store.begin(user, Type.SET_DEFAULT, Reason.USER, customer, "pm_a");
        assertThatThrownBy(() -> store.begin(user, Type.DETACH, Reason.USER, customer, "pm_a"))
                .isInstanceOf(SavedCardOperationStore.BusyException.class);
        // The database refuses it even without the application check.
        assertThatThrownBy(() -> jdbc.update("insert into saved_card_operations(user_id,operation_type,reason,provider_customer_id,"
                + "provider_payment_method_id,idempotency_key,status,created_at) values (?,'DETACH','USER',?,'pm_a','k-dup',"
                + "'PENDING',localtimestamp)", user, customer)).isInstanceOf(DataAccessException.class);
        store.finish(op.id(), Status.PENDING, Status.FAILED, "card_declined", null);
        assertThat(store.begin(user, Type.DETACH, Reason.USER, customer, "pm_a").status()).isEqualTo(Status.PENDING);
    }

    // ---- service ----------------------------------------------------------------------------------------------

    @Test
    void registrationCreatesTheCustomerOnceAndMakesTheFirstCardDefault() {
        assertThat(service.cards(user).cards()).isEmpty();
        var start = service.startRegistration(user);
        assertThat(payjp.customers).containsKey(customer);
        assertThat(start.clientSecret()).isNotBlank();
        assertThat(jdbc.queryForList("select provider_setup_flow_id from saved_card_operations where user_id=? and "
                + "operation_type='SETUP_START' and status='SUCCEEDED'", String.class, user)).containsExactly(start.setupFlowId());

        String pm = payjp.browserConfirms(start.setupFlowId(), "fp-visa", 2030);
        assertThat(service.completeRegistration(user, start.setupFlowId())).isEqualTo(SavedCardService.RegistrationResult.REGISTERED);
        assertThat(service.completeRegistration(user, start.setupFlowId())).isEqualTo(SavedCardService.RegistrationResult.REGISTERED);
        assertThat(payjp.customers.get(customer)).isEqualTo(pm);

        var view = service.cards(user);
        assertThat(view.cards()).singleElement().satisfies(c -> {
            assertThat(c.defaultCard()).isTrue();
            assertThat(c.last4()).isEqualTo("4242");
        });
        // A second registration does not create the customer again.
        service.startRegistration(user);
        assertThat(count("CUSTOMER_CREATE")).isEqualTo(1);
        // Nothing about the card itself is stored.
        assertThat(jdbc.queryForObject("select count(*) from saved_card_operations where user_id=? and "
                + "(error_code like '%4242%' or provider_payment_method_id like '%4242%')", Integer.class, user)).isZero();
    }

    @Test
    void duplicatesAndCardsOverTheLimitAreDetachedAgain() {
        register("fp-1");
        var start = service.startRegistration(user);
        String duplicate = payjp.browserConfirms(start.setupFlowId(), "fp-1", 2031);
        assertThat(service.completeRegistration(user, start.setupFlowId())).isEqualTo(SavedCardService.RegistrationResult.DUPLICATE);
        assertThat(payjp.cards.get(duplicate).detachedAt()).isNotNull();
        assertThat(service.completeRegistration(user, start.setupFlowId())).isEqualTo(SavedCardService.RegistrationResult.DUPLICATE);

        for (int i = 2; i <= SavedCardService.MAX_CARDS; i++) register("fp-" + i);
        assertThatThrownBy(() -> service.startRegistration(user)).isInstanceOf(SavedCardService.LimitException.class);
        // Two registrations started in parallel can still end with six cards: the newest one is removed.
        payjp.cards.values().stream().filter(c -> c.detachedAt() == null).findFirst().ifPresent(c -> payjp.detachDirect(c.id()));
        var a = service.startRegistration(user);
        var b = service.startRegistration(user);
        payjp.browserConfirms(a.setupFlowId(), "fp-a", 2030);
        String overflow = payjp.browserConfirms(b.setupFlowId(), "fp-b", 2030);
        assertThat(service.completeRegistration(user, a.setupFlowId())).isEqualTo(SavedCardService.RegistrationResult.REGISTERED);
        assertThat(service.completeRegistration(user, b.setupFlowId())).isEqualTo(SavedCardService.RegistrationResult.LIMIT);
        assertThat(payjp.cards.get(overflow).detachedAt()).isNotNull();
        assertThat(jdbc.queryForList("select reason from saved_card_operations where user_id=? and operation_type='DETACH' order by id",
                String.class, user)).containsExactly("DUPLICATE", "LIMIT");
    }

    @Test
    void deletingTheDefaultCardMakesTheNewestRemainingCardDefault() {
        String oldest = register("fp-1");
        String middle = register("fp-2");
        String newest = register("fp-3");
        assertThat(payjp.customers.get(customer)).isEqualTo(oldest);

        service.delete(user, middle);
        assertThat(payjp.customers.get(customer)).isEqualTo(oldest);
        service.delete(user, oldest);
        assertThat(payjp.customers.get(customer)).isEqualTo(newest);
        assertThat(jdbc.queryForList("select operation_type||':'||reason from saved_card_operations where user_id=? "
                + "and operation_type in ('DETACH','SET_DEFAULT') order by id", String.class, user))
                .containsExactly("SET_DEFAULT:DEFAULT_REPLACEMENT", "DETACH:USER", "DETACH:USER", "SET_DEFAULT:DEFAULT_REPLACEMENT");

        String another = register("fp-4");
        service.setDefault(user, another);
        assertThat(payjp.customers.get(customer)).isEqualTo(another);
    }

    @Test
    void otherPeoplesAndDeletedCardsAreRefused() {
        String mine = register("fp-1");
        payjp.cards.put("pm_foreign", card("pm_foreign", "someone-else", "fp-9", 2030, null));
        assertThatThrownBy(() -> service.delete(user, "pm_foreign")).isInstanceOf(SavedCardService.NotOwnedException.class);
        assertThatThrownBy(() -> service.setDefault(user, "pm_foreign")).isInstanceOf(SavedCardService.NotOwnedException.class);
        assertThatThrownBy(() -> service.delete(user, "not-a-card")).isInstanceOf(SavedCardService.NotOwnedException.class);
        service.delete(user, mine);
        assertThatThrownBy(() -> service.delete(user, mine)).isInstanceOf(SavedCardService.NotOwnedException.class);
        // A Setup Flow that this user did not start is never completed.
        assertThat(service.completeRegistration(user, "sfw_other")).isEqualTo(SavedCardService.RegistrationResult.UNKNOWN_FLOW);
        assertThat(service.completeRegistration(user, "bad id")).isEqualTo(SavedCardService.RegistrationResult.UNKNOWN_FLOW);
    }

    @Test
    void unknownResultIsSettledByReadingNotByResending() {
        register("fp-1");
        String second = register("fp-2");
        payjp.failNext = new SavedCardGatewayException(SavedCardGatewayException.Kind.UNKNOWN, null, "transport");
        payjp.applyDespiteFailure = true;
        assertThatThrownBy(() -> service.delete(user, second)).isInstanceOf(SavedCardGatewayException.class);
        assertThat(status("DETACH")).containsExactly("UNCONFIRMED");
        int sends = payjp.detachCalls;
        assertThatThrownBy(() -> store.begin(user, Type.SET_DEFAULT, Reason.USER, customer, second))
                .isInstanceOf(SavedCardOperationStore.BusyException.class);

        // While PAY.JP cannot be read the operation stays unconfirmed and blocks new operations.
        payjp.readsFail = true;
        assertThatThrownBy(() -> service.setDefault(user, second)).isInstanceOf(SavedCardGatewayException.class);
        payjp.readsFail = false;
        var view = service.cards(user);
        assertThat(view.busy()).isFalse();
        assertThat(status("DETACH")).containsExactly("SUCCEEDED");
        assertThat(payjp.detachCalls).isEqualTo(sends);
        assertThat(view.cards()).extracting(SavedCardService.CardView::id).doesNotContain(second);
    }

    @Test
    void rejectedSendsAreRecordedAsFailed() {
        String card = register("fp-1");
        payjp.failNext = new SavedCardGatewayException(SavedCardGatewayException.Kind.REJECTED, 400, "invalid_request_error");
        assertThatThrownBy(() -> service.setDefault(user, card)).isInstanceOf(SavedCardGatewayException.class);
        assertThat(jdbc.queryForObject("select status||':'||error_code from saved_card_operations where user_id=? "
                + "and operation_type='SET_DEFAULT' and reason='USER'", String.class, user)).isEqualTo("FAILED:invalid_request_error");
        // A failure does not block the next operation.
        service.setDefault(user, card);
    }

    @Test
    void lostSetupStartIsFoundByItsOperationId() {
        service.cards(user);
        payjp.failNext = new SavedCardGatewayException(SavedCardGatewayException.Kind.UNKNOWN, null, "transport");
        payjp.applyDespiteFailure = true;
        // The customer is created first; the Setup Flow creation reaches PAY.JP but its answer is lost.
        payjp.failOn = "setup";
        assertThatThrownBy(() -> service.startRegistration(user)).isInstanceOf(SavedCardGatewayException.class);
        assertThat(status("SETUP_START")).containsExactly("UNCONFIRMED");
        service.cards(user);
        assertThat(status("SETUP_START")).containsExactly("SUCCEEDED");
        assertThat(jdbc.queryForObject("select provider_setup_flow_id from saved_card_operations where user_id=? and "
                + "operation_type='SETUP_START'", String.class, user)).isEqualTo(payjp.setups.keySet().iterator().next());
    }

    @Test
    void expiredCardsAreMarked() {
        service.setClock(java.time.Clock.fixed(java.time.Instant.parse("2031-01-01T00:00:00Z"), java.time.ZoneId.of("Asia/Tokyo")));
        register("fp-1");
        var start = service.startRegistration(user);
        payjp.browserConfirms(start.setupFlowId(), "fp-2", 2031);
        service.completeRegistration(user, start.setupFlowId());
        assertThat(service.cards(user).cards()).extracting(SavedCardService.CardView::expired).containsExactly(false, true);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private String register(String fingerprint) {
        var start = service.startRegistration(user);
        String pm = payjp.browserConfirms(start.setupFlowId(), fingerprint, 2030);
        assertThat(service.completeRegistration(user, start.setupFlowId())).isEqualTo(SavedCardService.RegistrationResult.REGISTERED);
        return pm;
    }

    private int count(String type) {
        return jdbc.queryForObject("select count(*) from saved_card_operations where user_id=? and operation_type=?",
                Integer.class, user, type);
    }

    private List<String> status(String type) {
        return jdbc.queryForList("select status from saved_card_operations where user_id=? and operation_type=? order by id",
                String.class, user, type);
    }

    static SavedCardGateway.Card card(String id, String customer, String fingerprint, int expYear, OffsetDateTime detachedAt) {
        return new SavedCardGateway.Card(id, customer, "Visa", "4242", 12, expYear, fingerprint,
                OffsetDateTime.now(ZoneOffset.UTC), detachedAt);
    }

    /** In-memory PAY.JP: customers (id → default card), cards and Setup Flows. Ordered creation times. */
    static final class FakePayJp implements SavedCardGateway {
        final Map<String, String> customers = new LinkedHashMap<>();
        final Map<String, Card> cards = new LinkedHashMap<>();
        final Map<String, SetupState> setups = new LinkedHashMap<>();
        final Map<String, Long> setupOperations = new LinkedHashMap<>();
        SavedCardGatewayException failNext;
        boolean applyDespiteFailure;
        String failOn;
        boolean readsFail;
        int detachCalls;
        int sequence;
        OffsetDateTime clock = OffsetDateTime.parse("2026-11-22T00:00:00Z");

        private void read() {
            if (readsFail) throw new SavedCardGatewayException(SavedCardGatewayException.Kind.UNKNOWN, null, "transport");
        }

        private <T> T write(String kind, java.util.function.Supplier<T> action) {
            if (failNext != null && (failOn == null || failOn.equals(kind))) {
                var failure = failNext;
                failNext = null;
                failOn = null;
                if (applyDespiteFailure) action.get();
                applyDespiteFailure = false;
                throw failure;
            }
            return action.get();
        }

        String browserConfirms(String setupFlowId, String fingerprint, int expYear) {
            var setup = setups.get(setupFlowId);
            String id = "pm_" + (++sequence) + "_" + UUID.randomUUID().toString().replace("-", "");
            clock = clock.plusMinutes(1);
            cards.put(id, new Card(id, setup.customerId(), "Visa", "4242", 12, expYear, fingerprint, clock, null));
            setups.put(setupFlowId, new SetupState(setupFlowId, setup.customerId(), "succeeded", id));
            return id;
        }

        void detachDirect(String id) {
            var c = cards.get(id);
            cards.put(id, new Card(id, c.customerId(), c.brand(), c.last4(), c.expMonth(), c.expYear(), c.fingerprint(),
                    c.createdAt(), clock));
            if (id.equals(customers.get(c.customerId()))) customers.put(c.customerId(), null);
        }

        @Override public Optional<Customer> findCustomer(String id) {
            read();
            return customers.containsKey(id) ? Optional.of(new Customer(id, customers.get(id))) : Optional.empty();
        }

        @Override public Customer createCustomer(String id, long userId, String key) {
            return write("customer", () -> {
                if (customers.containsKey(id))
                    throw new SavedCardGatewayException(SavedCardGatewayException.Kind.REJECTED, 400, "already_exists_id");
                customers.put(id, null);
                return new Customer(id, null);
            });
        }

        @Override public List<Card> listCards(String customer) {
            read();
            return new ArrayList<>(cards.values().stream().filter(c -> c.attachedTo(customer)).toList());
        }

        @Override public Optional<Card> findCard(String id) {
            read();
            return Optional.ofNullable(cards.get(id));
        }

        @Override public SetupStart createSetupFlow(String customer, long userId, long operationId, String key) {
            return write("setup", () -> {
                String id = "sfw_" + (++sequence) + "_" + UUID.randomUUID().toString().replace("-", "");
                setups.put(id, new SetupState(id, customer, "requires_payment_method", null));
                setupOperations.put(id, operationId);
                return new SetupStart(id, id + "_secret");
            });
        }

        @Override public Optional<SetupState> findSetupFlow(String id) {
            read();
            return Optional.ofNullable(setups.get(id));
        }

        @Override public List<SetupState> findSetupFlowsByOperation(String customer, long operationId) {
            read();
            return setups.values().stream().filter(s -> customer.equals(s.customerId())
                    && Long.valueOf(operationId).equals(setupOperations.get(s.id()))).toList();
        }

        @Override public Card detach(String id, String key) {
            return write("detach", () -> {
                detachCalls++;
                var c = cards.get(id);
                if (c.detachedAt() != null)
                    throw new SavedCardGatewayException(SavedCardGatewayException.Kind.REJECTED, 400, "payment_method_already_detached");
                detachDirect(id);
                return cards.get(id);
            });
        }

        @Override public Customer setDefaultCard(String customer, String pm, String key) {
            return write("default", () -> {
                customers.put(customer, pm);
                return new Customer(customer, pm);
            });
        }

        // ---- Feature139-B: checkout Payment Flows ------------------------------------------------------------
        final Map<String, FlowState> flows = new LinkedHashMap<>();
        /** Cards that already did 3-D Secure (later confirms authorize at once). */
        final java.util.Set<String> secured = new java.util.HashSet<>();
        /** Cards PAY.JP declines on confirm (402). */
        final java.util.Set<String> declining = new java.util.HashSet<>();
        int updateCalls;
        int confirmCalls;

        void newFlow(String id) {
            flows.put(id, new FlowState(id, "requires_payment_method", null, null));
        }

        /** The browser finished 3-D Secure (or confirmed a new card) on the flow. */
        void browserCompletes(String flowId, String status, String paymentMethodId) {
            var f = flows.get(flowId);
            flows.put(flowId, new FlowState(flowId, status, f.customerId(), paymentMethodId != null ? paymentMethodId : f.paymentMethodId()));
        }

        private static SavedCardGatewayException rejected(int status, String code) {
            return new SavedCardGatewayException(SavedCardGatewayException.Kind.REJECTED, status, code);
        }

        @Override public FlowState updateFlowCustomer(String flowId, String customerId, String pm, String key) {
            return write("flowUpdate", () -> {
                updateCalls++;
                var f = flows.get(flowId);
                if (!f.status().equals("requires_payment_method") && !f.status().equals("requires_confirmation"))
                    throw rejected(400, "fields_not_updatable_in_status");
                if (pm != null && (cards.get(pm) == null || !cards.get(pm).attachedTo(customerId)))
                    throw rejected(400, "payment_method_not_owned_by_customer");
                var next = new FlowState(flowId, pm != null ? "requires_confirmation" : f.status(), customerId,
                        pm != null ? pm : f.paymentMethodId());
                flows.put(flowId, next);
                return next;
            });
        }

        @Override public FlowState confirmFlow(String flowId, String returnUrl, String key) {
            return write("confirm", () -> {
                confirmCalls++;
                var f = flows.get(flowId);
                if (!f.status().equals("requires_payment_method") && !f.status().equals("requires_confirmation"))
                    throw rejected(400, "invalid_status");
                if (declining.contains(f.paymentMethodId())) {
                    flows.put(flowId, new FlowState(flowId, "requires_payment_method", f.customerId(), f.paymentMethodId()));
                    throw rejected(402, "card_declined");
                }
                var next = new FlowState(flowId, secured.contains(f.paymentMethodId()) ? "requires_capture" : "requires_action",
                        f.customerId(), f.paymentMethodId());
                flows.put(flowId, next);
                return next;
            });
        }

        @Override public Optional<FlowState> findFlow(String flowId) {
            read();
            return Optional.ofNullable(flows.get(flowId));
        }

        @Override public Card attach(String id, String customer, String key) {
            return write("attach", () -> {
                var c = cards.get(id);
                if (c.customerId() != null) throw rejected(400, "payment_method_already_attached");
                cards.put(id, new Card(id, customer, c.brand(), c.last4(), c.expMonth(), c.expYear(), c.fingerprint(),
                        c.createdAt(), null));
                return cards.get(id);
            });
        }

        /** A card entered in payments.js on a checkout (not attached to any customer). */
        String browserCard(String fingerprint) {
            String id = "pm_" + (++sequence) + "_" + UUID.randomUUID().toString().replace("-", "");
            clock = clock.plusMinutes(1);
            cards.put(id, new Card(id, null, "Visa", "4242", 12, 2030, fingerprint, clock, null));
            return id;
        }
    }
}
