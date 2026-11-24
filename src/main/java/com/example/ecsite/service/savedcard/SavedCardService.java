package com.example.ecsite.service.savedcard;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ecsite.config.PayJpCustomerProperties;
import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.config.PayJpSafetyProperties;
import com.example.ecsite.payment.savedcard.SavedCardGateway;
import com.example.ecsite.payment.savedcard.SavedCardGateway.Card;
import com.example.ecsite.payment.savedcard.SavedCardGateway.Customer;
import com.example.ecsite.payment.savedcard.SavedCardGatewayException;
import com.example.ecsite.service.savedcard.SavedCardOperationStore.Operation;
import com.example.ecsite.service.savedcard.SavedCardOperationStore.Reason;
import com.example.ecsite.service.savedcard.SavedCardOperationStore.Status;
import com.example.ecsite.service.savedcard.SavedCardOperationStore.Type;

/**
 * Feature139-A (docs/feature139/saved-cards.md ch.4-5): saved cards on the user's PAY.JP customer.
 * Nothing about cards is stored locally; every send to PAY.JP is recorded before it is made and its result after,
 * outside any transaction. An unknown result is confirmed by reading PAY.JP again, never by sending again.
 */
@Service
public class SavedCardService {

    public static final int MAX_CARDS = 5;
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Logger log = LoggerFactory.getLogger(SavedCardService.class);

    public record CardView(String id, String brand, String last4, int expMonth, int expYear, boolean defaultCard,
            boolean expired) {}

    public record CardsView(List<CardView> cards, boolean busy, boolean defaultMissing) {
        public boolean canAdd() { return !busy && cards.size() < MAX_CARDS; }
    }

    public record CheckoutCards(List<CardView> usable, boolean canRegister) {}

    public record RegistrationStart(String setupFlowId, String clientSecret) {
        @Override public String toString() { return "RegistrationStart[" + setupFlowId + ", redacted]"; }
    }

    public enum RegistrationResult { REGISTERED, DUPLICATE, LIMIT, NOT_COMPLETED, UNKNOWN_FLOW }

    /** Saved cards are not configured (no customer id prefix). */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException() { super("Saved cards are not available"); }
    }

    /** The card is not an attached card of this user's customer. */
    public static class NotOwnedException extends RuntimeException {
        public NotOwnedException() { super("Card not found"); }
    }

    public static class LimitException extends RuntimeException {
        public LimitException() { super("Saved card limit reached"); }
    }

    private final PayJpCustomerProperties customers;
    private final PayJpProperties payjp;
    private final SavedCardGateway gateway;
    private final SavedCardOperationStore store;
    private final Duration pendingAge;
    private java.time.Clock clock = java.time.Clock.system(TOKYO);

    public SavedCardService(PayJpCustomerProperties customers, PayJpProperties payjp, SavedCardGateway gateway,
            SavedCardOperationStore store, PayJpSafetyProperties safety) {
        this.customers = customers;
        this.payjp = payjp;
        this.gateway = gateway;
        this.store = store;
        // A PENDING send older than this can no longer be running (connect + read timeouts, with margin).
        this.pendingAge = safety.connectTimeout().plus(safety.readTimeout()).plusMinutes(1);
    }

    /** Test seam for the expiry display. */
    void setClock(java.time.Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public boolean enabled() {
        return customers.enabled() && payjp.publicKey() != null && !payjp.publicKey().isBlank();
    }

    public String publicKey() {
        requireEnabled();
        return payjp.publicKey();
    }

    public CardsView cards(long userId) {
        requireEnabled();
        settle(userId);
        boolean busy = !store.inFlight(userId).isEmpty();
        String customerId = customers.customerId(userId);
        Optional<Customer> customer = gateway.findCustomer(customerId);
        if (customer.isEmpty()) return new CardsView(List.of(), busy, false);
        List<Card> cards = newestFirst(gateway.listCards(customerId));
        String defaultId = customer.get().defaultPaymentMethodId();
        LocalDate today = LocalDate.now(clock);
        var views = cards.stream().map(c -> new CardView(c.id(), c.brand(), c.last4(), c.expMonth(), c.expYear(),
                c.id().equals(defaultId), expired(c, today))).toList();
        boolean defaultMissing = !cards.isEmpty() && views.stream().noneMatch(CardView::defaultCard);
        return new CardsView(views, busy, defaultMissing);
    }

    public RegistrationStart startRegistration(long userId) {
        requireEnabled();
        settle(userId);
        String customerId = ensureCustomer(userId);
        if (gateway.listCards(customerId).size() >= MAX_CARDS) throw new LimitException();
        Operation op = store.begin(userId, Type.SETUP_START, Reason.USER, customerId, null);
        var start = send(op, () -> gateway.createSetupFlow(customerId, userId, op.id(), op.idempotencyKey()),
                SavedCardGateway.SetupStart::id);
        return new RegistrationStart(start.id(), start.clientSecret());
    }

    /** The browser returned from {@code widgets.confirmSetup}. Safe to repeat (reloads). */
    public RegistrationResult completeRegistration(long userId, String setupFlowId) {
        requireEnabled();
        if (setupFlowId == null || !setupFlowId.matches("[A-Za-z0-9_-]{1,255}")) return RegistrationResult.UNKNOWN_FLOW;
        String customerId = customers.customerId(userId);
        if (store.startedSetup(userId, setupFlowId).filter(s -> customerId.equals(s.customerId())).isEmpty())
            return RegistrationResult.UNKNOWN_FLOW;
        var state = gateway.findSetupFlow(setupFlowId).orElse(null);
        if (state == null || !"succeeded".equals(state.status()) || state.paymentMethodId() == null)
            return RegistrationResult.NOT_COMPLETED;
        if (!customerId.equals(state.customerId())) throw new IllegalStateException("Setup Flow customer mismatch");
        String added = state.paymentMethodId();
        store.recordSetupCompletion(userId, customerId, setupFlowId, added);
        settle(userId);
        return settleAddedCard(userId, customerId, added);
    }

    /**
     * After a card was added to the customer (Setup Flow or checkout): refuse a duplicate, keep at most
     * {@link #MAX_CARDS} cards (the newest beyond the limit is detached) and make sure there is a default card.
     * Safe to repeat.
     */
    RegistrationResult settleAddedCard(long userId, String customerId, String added) {
        List<Card> cards = gateway.listCards(customerId);
        Card card = cards.stream().filter(c -> c.id().equals(added)).findFirst().orElse(null);
        if (card == null) {
            // Already handled by an earlier visit (or deleted since): report what this site did to it.
            return switch (store.detachReason(userId, added).orElse(Reason.USER)) {
                case DUPLICATE -> RegistrationResult.DUPLICATE;
                case LIMIT -> RegistrationResult.LIMIT;
                default -> RegistrationResult.REGISTERED;
            };
        }
        boolean duplicate = card.fingerprint() != null && cards.stream()
                .anyMatch(c -> !c.id().equals(added) && card.fingerprint().equals(c.fingerprint()));
        if (duplicate) {
            detach(userId, customerId, added, Reason.DUPLICATE);
            ensureDefault(userId, customerId);
            return RegistrationResult.DUPLICATE;
        }
        // Registrations started in parallel can exceed the limit: only the cards beyond it (newest) are removed.
        List<Card> oldestFirst = newestFirst(cards).reversed();
        if (oldestFirst.indexOf(card) >= MAX_CARDS) {
            detach(userId, customerId, added, Reason.LIMIT);
            ensureDefault(userId, customerId);
            return RegistrationResult.LIMIT;
        }
        ensureDefault(userId, customerId);
        return RegistrationResult.REGISTERED;
    }

    // ---- Feature139-B: used by the checkout -------------------------------------------------------------------

    /** The customer id of the user (no PAY.JP call). */
    public String customerIdOf(long userId) {
        requireEnabled();
        return customers.customerId(userId);
    }

    /** Creates the PAY.JP customer when missing. */
    public String prepareCustomer(long userId) {
        requireEnabled();
        settle(userId);
        return ensureCustomer(userId);
    }

    /** Cards that can pay now (attached, not expired), newest first, and whether one more card can be registered. */
    public CheckoutCards cardsForCheckout(long userId) {
        var view = cards(userId);
        return new CheckoutCards(view.cards().stream().filter(c -> !c.expired()).toList(), view.canAdd());
    }

    /** Throws {@link NotOwnedException} unless the card is an attached, unexpired card of this user's customer. */
    public void requireUsableCard(long userId, String paymentMethodId) {
        requireEnabled();
        String customerId = customers.customerId(userId);
        if (paymentMethodId == null || !paymentMethodId.matches("pm_[A-Za-z0-9_-]{1,252}")) throw new NotOwnedException();
        Card card = gateway.findCard(paymentMethodId).orElseThrow(NotOwnedException::new);
        if (!card.attachedTo(customerId) || expired(card, LocalDate.now(clock))) throw new NotOwnedException();
    }

    /**
     * After a checkout authorized with a new card the user chose to register: attach it when PAY.JP did not, then
     * apply the duplicate / limit / default rules. Empty when the card belongs to another customer.
     */
    public Optional<RegistrationResult> registerCheckoutCard(long userId, String paymentMethodId) {
        requireEnabled();
        settle(userId);
        String customerId = customers.customerId(userId);
        Card card = gateway.findCard(paymentMethodId).orElseThrow(NotOwnedException::new);
        if (card.detachedAt() == null && card.customerId() != null && !customerId.equals(card.customerId())) {
            log.warn("Checkout card {} belongs to another customer; not registered", paymentMethodId);
            return Optional.empty();
        }
        if (card.detachedAt() == null && card.customerId() == null) {
            Operation op = store.begin(userId, Type.ATTACH, Reason.USER, customerId, paymentMethodId);
            send(op, () -> gateway.attach(paymentMethodId, customerId, op.idempotencyKey()), r -> null);
        }
        return Optional.of(settleAddedCard(userId, customerId, paymentMethodId));
    }

    public void delete(long userId, String paymentMethodId) {
        requireEnabled();
        settle(userId);
        String customerId = customers.customerId(userId);
        requireOwned(customerId, paymentMethodId);
        detach(userId, customerId, paymentMethodId, Reason.USER);
        // Decision 6 (plan B): when the default card is gone, the newest remaining card becomes the default.
        ensureDefault(userId, customerId);
    }

    public void setDefault(long userId, String paymentMethodId) {
        requireEnabled();
        settle(userId);
        String customerId = customers.customerId(userId);
        requireOwned(customerId, paymentMethodId);
        Operation op = store.begin(userId, Type.SET_DEFAULT, Reason.USER, customerId, paymentMethodId);
        send(op, () -> gateway.setDefaultCard(customerId, paymentMethodId, op.idempotencyKey()), r -> null);
    }

    /** Confirms PENDING (stale) and UNCONFIRMED operations by reading PAY.JP. Never sends anything again. */
    void settle(long userId) {
        for (Operation op : store.inFlight(userId)) {
            if (op.status() == Status.PENDING && !store.pendingOlderThan(op.id(), pendingAge)) continue;
            try {
                Optional<Boolean> applied = observe(op);
                if (applied.isEmpty()) continue;
                if (applied.get()) {
                    String setupFlowId = op.type() == Type.SETUP_START ? setupFlowOf(op) : null;
                    store.finish(op.id(), op.status(), Status.SUCCEEDED, null, setupFlowId);
                } else {
                    store.finish(op.id(), op.status(), Status.FAILED, "not_applied", null);
                }
            } catch (SavedCardGatewayException stillUnknown) {
                log.warn("Saved card operation {} is still unconfirmed: {}", op.id(), stillUnknown.getMessage());
            }
        }
    }

    /** Empty when PAY.JP does not tell yet. */
    private Optional<Boolean> observe(Operation op) {
        return switch (op.type()) {
            case CUSTOMER_CREATE -> Optional.of(gateway.findCustomer(op.customerId()).isPresent());
            case SETUP_START -> {
                int found = gateway.findSetupFlowsByOperation(op.customerId(), op.id()).size();
                yield found > 1 ? Optional.empty() : Optional.of(found == 1);
            }
            case DETACH -> gateway.findCard(op.paymentMethodId()).map(c -> c.detachedAt() != null);
            case SET_DEFAULT -> gateway.findCustomer(op.customerId())
                    .map(c -> op.paymentMethodId().equals(c.defaultPaymentMethodId()));
            case ATTACH -> gateway.findCard(op.paymentMethodId())
                    .map(c -> c.detachedAt() == null && op.customerId().equals(c.customerId()));
            case SETUP_COMPLETE -> Optional.empty();
        };
    }

    private String setupFlowOf(Operation op) {
        return gateway.findSetupFlowsByOperation(op.customerId(), op.id()).getFirst().id();
    }

    private String ensureCustomer(long userId) {
        String customerId = customers.customerId(userId);
        if (gateway.findCustomer(customerId).isPresent()) return customerId;
        Operation op = store.begin(userId, Type.CUSTOMER_CREATE, Reason.USER, customerId, null);
        try {
            send(op, () -> gateway.createCustomer(customerId, userId, op.idempotencyKey()), r -> null);
        } catch (SavedCardGatewayException failure) {
            // Another request created it first (same deterministic id): read it back instead of failing.
            if (!"already_exists_id".equals(failure.code()) || gateway.findCustomer(customerId).isEmpty()) throw failure;
        }
        return customerId;
    }

    private void detach(long userId, String customerId, String paymentMethodId, Reason reason) {
        Operation op = store.begin(userId, Type.DETACH, reason, customerId, paymentMethodId);
        try {
            send(op, () -> gateway.detach(paymentMethodId, op.idempotencyKey()), r -> null);
        } catch (SavedCardGatewayException failure) {
            if (!"payment_method_already_detached".equals(failure.code())) throw failure;
            // Recorded as FAILED with that code; the card is gone either way.
        }
    }

    /** Sets the newest card as the default when there is no default among the attached cards. */
    private void ensureDefault(long userId, String customerId) {
        Customer customer = gateway.findCustomer(customerId).orElse(null);
        if (customer == null) return;
        List<Card> cards = newestFirst(gateway.listCards(customerId));
        if (cards.isEmpty() || cards.stream().anyMatch(c -> c.id().equals(customer.defaultPaymentMethodId()))) return;
        String newest = cards.getFirst().id();
        Operation op = store.begin(userId, Type.SET_DEFAULT, Reason.DEFAULT_REPLACEMENT, customerId, newest);
        send(op, () -> gateway.setDefaultCard(customerId, newest, op.idempotencyKey()), r -> null);
    }

    private void requireOwned(String customerId, String paymentMethodId) {
        if (paymentMethodId == null || !paymentMethodId.matches("pm_[A-Za-z0-9_-]{1,252}")) throw new NotOwnedException();
        Card card = gateway.findCard(paymentMethodId).orElseThrow(NotOwnedException::new);
        if (!card.attachedTo(customerId)) throw new NotOwnedException();
    }

    private <T> T send(Operation op, Supplier<T> call, java.util.function.Function<T, String> setupFlowId) {
        T result;
        try {
            result = call.get();
        } catch (SavedCardGatewayException failure) {
            store.finish(op.id(), Status.PENDING, failure.kind() == SavedCardGatewayException.Kind.REJECTED
                    ? Status.FAILED : Status.UNCONFIRMED, failure.code(), null);
            throw failure;
        } catch (IllegalArgumentException invalid) {
            // Raised before anything was sent.
            store.finish(op.id(), Status.PENDING, Status.FAILED, "invalid_request", null);
            throw invalid;
        } catch (RuntimeException unexpected) {
            store.finish(op.id(), Status.PENDING, Status.UNCONFIRMED, "unexpected", null);
            throw unexpected;
        }
        if (store.finish(op.id(), Status.PENDING, Status.SUCCEEDED, null, setupFlowId.apply(result)).isEmpty())
            log.warn("Saved card operation {} was confirmed by another request first", op.id());
        return result;
    }

    private void requireEnabled() {
        if (!enabled()) throw new UnavailableException();
    }

    private static List<Card> newestFirst(List<Card> cards) {
        return cards.stream().sorted(Comparator.comparing(Card::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Card::id, Comparator.reverseOrder())).toList();
    }

    private static boolean expired(Card card, LocalDate today) {
        return LocalDate.of(card.expYear(), card.expMonth(), 1).plusMonths(1).isBefore(today.plusDays(1));
    }
}
