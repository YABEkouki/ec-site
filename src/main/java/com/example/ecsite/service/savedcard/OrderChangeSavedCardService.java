package com.example.ecsite.service.savedcard;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.config.PayJpSafetyProperties;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation;
import com.example.ecsite.payment.savedcard.SavedCardGateway;
import com.example.ecsite.payment.savedcard.SavedCardGatewayException;
import com.example.ecsite.service.orderchange.OrderChangeBrowserService.Card;
import com.example.ecsite.service.orderchange.OrderReauthorizationTransactions;
import com.example.ecsite.service.savedcard.OrderChangeCardSendStore.Send;
import com.example.ecsite.service.savedcard.OrderChangeCardSendStore.SendType;
import com.example.ecsite.service.savedcard.OrderChangeCardSendStore.Status;

/**
 * Feature139-C (docs/feature139/saved-cards.md ch.7): a saved card for the order edit reauthorization. This site
 * records the one-time confirmation start exactly like the card entry screen, then sends the update (customer / card)
 * and the confirm. Everything after that — the authorization, the old authorization cancel, the switch, the
 * not-adopted cancel and the 3-D Secure failure closure — is the existing code reading the Payment Flow. Sends are
 * recorded before and after, outside transactions; unknown results are confirmed by reading, never by resending.
 */
@Service
public class OrderChangeSavedCardService {

    public enum Outcome {
        /** The confirm needs 3-D Secure in the browser ({@link Result#card()} is set). */
        REQUIRES_ACTION,
        /** Sent (or its result is being confirmed): continue with the existing server-side result check. */
        CONTINUE,
        /** The card is not a usable saved card of the user. Nothing was sent. */
        CARD_NOT_USABLE,
        /** PAY.JP refused the card update. The confirmation chance of this change is used. */
        UPDATE_REFUSED,
        /** The change cannot start or continue a saved card payment now. Nothing was sent. */
        NOT_AVAILABLE
    }

    public record Result(Outcome outcome, Card card) {
        static Result of(Outcome outcome) { return new Result(outcome, null); }
        @Override public String toString() { return "OrderChangeSavedCardResult[" + outcome + "]"; }
    }

    /** What the result page may offer for a saved card payment of the change (read from saved rows only). */
    public enum NextStep { NONE, AUTHENTICATE, RESEND }

    private static final Logger log = LoggerFactory.getLogger(OrderChangeSavedCardService.class);

    private final SavedCardService cards;
    private final SavedCardGateway gateway;
    private final OrderChangeCardSendStore sends;
    private final OrderReauthorizationTransactions transactions;
    private final PaymentGateway payments;
    private final PayJpProperties provider;
    private final JdbcTemplate jdbc;
    private final Duration pendingAge;
    private final String baseUrl;

    public OrderChangeSavedCardService(SavedCardService cards, SavedCardGateway gateway, OrderChangeCardSendStore sends,
            OrderReauthorizationTransactions transactions, PaymentGateway payments, PayJpProperties provider,
            JdbcTemplate jdbc, PayJpSafetyProperties safety, @Value("${app.base-url}") String baseUrl) {
        this.cards = cards;
        this.gateway = gateway;
        this.sends = sends;
        this.transactions = transactions;
        this.payments = payments;
        this.provider = provider;
        this.jdbc = jdbc;
        this.pendingAge = safety.connectTimeout().plus(safety.readTimeout()).plusMinutes(1);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public boolean enabled() {
        return cards.enabled();
    }

    /** Usable saved cards for the card choice screen; empty when there is none or PAY.JP cannot be read. */
    public List<SavedCardService.CardView> options(long userId) {
        if (!enabled()) return List.of();
        try {
            return cards.cardsForCheckout(userId).usable();
        } catch (SavedCardGatewayException | SavedCardService.UnavailableException failure) {
            log.warn("Saved cards unavailable for an order change: {}", failure.getMessage());
            return List.of();
        }
    }

    /** Pays the reauthorization of the change with a saved card, or resends a confirm that was not delivered. */
    public Result pay(long userId, long orderId, UUID admission, String paymentMethodId) {
        outside();
        if (!enabled()) return Result.of(Outcome.NOT_AVAILABLE);
        try {
            cards.requireUsableCard(userId, paymentMethodId);
        } catch (SavedCardService.NotOwnedException notUsable) {
            return Result.of(Outcome.CARD_NOT_USABLE);
        } catch (SavedCardGatewayException | SavedCardService.UnavailableException unreadable) {
            return Result.of(Outcome.NOT_AVAILABLE);
        }
        List<Send> existing = owned(userId, orderId, admission);
        if (!existing.isEmpty()) return resend(existing, paymentMethodId);

        var prepared = transactions.prepareOrderEditBrowser(orderId, userId, admission);
        if (prepared.isEmpty()) return Result.of(Outcome.NOT_AVAILABLE);
        var target = prepared.get();
        String customerId = cards.customerIdOf(userId);
        long started = System.nanoTime();
        final PayJpPaymentFlowObservation observed;
        try {
            observed = payments.observeOrderChangeFlow(target.flow());
        } catch (RuntimeException unreadable) {
            return Result.of(Outcome.NOT_AVAILABLE);
        }
        if (observed == null || !target.flow().equals(observed.id()) || !"requires_payment_method".equals(observed.status()))
            return Result.of(Outcome.NOT_AVAILABLE);
        final Optional<Send> update;
        try {
            update = transactions.issueOrderEditSavedCard(target, observed, started,
                    t -> sends.insertUpdate(userId, t.order(), t.operation(), t.admission(), t.payment(), t.authorization(),
                            t.flow(), customerId, paymentMethodId));
        } catch (RuntimeException notIssued) {
            log.warn("Saved card confirmation start was not recorded for order {}: {}", orderId, notIssued.getMessage());
            return Result.of(Outcome.NOT_AVAILABLE);
        }
        if (update.isEmpty()) return Result.of(Outcome.NOT_AVAILABLE);

        Status updated = send(update.get(), () -> gateway.updateFlowCustomer(update.get().flowId(), customerId,
                paymentMethodId, update.get().idempotencyKey()), null);
        if (updated == Status.FAILED) return Result.of(Outcome.UPDATE_REFUSED);
        if (updated != Status.SUCCEEDED) return Result.of(Outcome.CONTINUE);
        return confirm(update.get());
    }

    /** Restarts the browser 3-D Secure of a confirm this site sent. Never sends the confirm again. */
    public Optional<Card> authenticate(long userId, long orderId, UUID admission) {
        outside();
        if (!enabled()) return Optional.empty();
        List<Send> all = owned(userId, orderId, admission);
        if (all.isEmpty()) return Optional.empty();
        settle(all.getFirst().operationId());
        var confirm = latestConfirm(sends.forOperation(all.getFirst().operationId()));
        if (confirm.isEmpty() || confirm.get().status() != Status.SUCCEEDED) return Optional.empty();
        return actionCard(confirm.get());
    }

    /** Display hint for the result page; reads saved rows only (no PAY.JP call, no write). */
    public NextStep nextStep(long userId, long orderId, UUID admission) {
        if (!enabled()) return NextStep.NONE;
        List<Send> all = owned(userId, orderId, admission);
        if (all.isEmpty()) return NextStep.NONE;
        var state = jdbc.queryForList("select op.state from order_change_operations op join order_edit_admissions a "
                + "on a.order_id=op.order_id and a.submission_key=op.submission_key where a.id=? and op.id=? "
                + "and a.closed_at is null and op.switch_decided_at is null and op.abort_request_id is null",
                String.class, admission, all.getFirst().operationId());
        if (state.size() != 1) return NextStep.NONE;
        var update = all.stream().filter(s -> s.type() == SendType.FLOW_UPDATE).findFirst();
        var confirm = latestConfirm(all);
        if (confirm.isPresent() && confirm.get().status() == Status.SUCCEEDED && "REQUIRES_ACTION".equals(state.getFirst()))
            return NextStep.AUTHENTICATE;
        boolean unresolved = all.stream().anyMatch(s -> s.status() == Status.PENDING || s.status() == Status.UNCONFIRMED);
        boolean resendable = update.filter(u -> u.status() == Status.SUCCEEDED).isPresent()
                && (confirm.isEmpty() || confirm.get().status() == Status.FAILED)
                && all.stream().filter(s -> s.type() == SendType.CONFIRM).count() < OrderChangeCardSendStore.MAX_CONFIRMS;
        return "AUTHORIZING".equals(state.getFirst()) && (unresolved || resendable) ? NextStep.RESEND : NextStep.NONE;
    }

    /** The card the update set (for the resend form). */
    public Optional<String> selectedCard(long userId, long orderId, UUID admission) {
        return owned(userId, orderId, admission).stream().filter(s -> s.type() == SendType.FLOW_UPDATE)
                .map(Send::paymentMethodId).findFirst();
    }

    private Result resend(List<Send> existing, String paymentMethodId) {
        UUID operation = existing.getFirst().operationId();
        settle(operation);
        List<Send> all = sends.forOperation(operation);
        var update = all.stream().filter(s -> s.type() == SendType.FLOW_UPDATE).findFirst().orElseThrow();
        if (!update.paymentMethodId().equals(paymentMethodId)) return Result.of(Outcome.NOT_AVAILABLE);
        if (all.stream().anyMatch(s -> s.status() == Status.PENDING || s.status() == Status.UNCONFIRMED))
            return Result.of(Outcome.CONTINUE);
        if (update.status() == Status.FAILED) return Result.of(Outcome.UPDATE_REFUSED);
        var confirm = latestConfirm(all);
        if (confirm.isPresent() && confirm.get().status() == Status.SUCCEEDED)
            return actionCard(confirm.get()).map(c -> new Result(Outcome.REQUIRES_ACTION, c)).orElse(Result.of(Outcome.CONTINUE));
        long confirms = all.stream().filter(s -> s.type() == SendType.CONFIRM).count();
        if (confirms >= OrderChangeCardSendStore.MAX_CONFIRMS) return Result.of(Outcome.NOT_AVAILABLE);
        try {
            var flow = gateway.findFlow(update.flowId()).orElse(null);
            if (flow == null || !"requires_confirmation".equals(flow.status())) return Result.of(Outcome.CONTINUE);
        } catch (SavedCardGatewayException unreadable) {
            return Result.of(Outcome.NOT_AVAILABLE);
        }
        return confirm(update);
    }

    private Result confirm(Send update) {
        final Send confirm;
        try {
            confirm = sends.beginConfirm(update);
        } catch (OrderChangeCardSendStore.BusyException busy) {
            return Result.of(Outcome.CONTINUE);
        } catch (RuntimeException refused) {
            // The trigger refuses after the deadline, after a switch decision or abort, or when not delivered twice.
            log.warn("Saved card confirm was not started for order {}: {}", update.orderId(), refused.getMessage());
            return Result.of(Outcome.NOT_AVAILABLE);
        }
        var response = new AtomicReference<SavedCardGateway.FlowState>();
        Status status = send(confirm, () -> gateway.confirmFlow(confirm.flowId(), returnUrl(confirm), confirm.idempotencyKey()),
                response);
        if (status == Status.SUCCEEDED && response.get() != null && "requires_action".equals(response.get().status()))
            return actionCard(confirm).map(c -> new Result(Outcome.REQUIRES_ACTION, c)).orElse(Result.of(Outcome.CONTINUE));
        return Result.of(Outcome.CONTINUE);
    }

    private Optional<Card> actionCard(Send confirm) {
        try {
            var observed = payments.observeOrderChangeFlow(confirm.flowId());
            if (observed == null || !confirm.flowId().equals(observed.id()) || !"requires_action".equals(observed.status()))
                return Optional.empty();
            var recovered = payments.retrieveAuthorization(confirm.flowId());
            if (recovered == null || recovered.clientSecret() == null || recovered.clientSecret().isBlank()
                    || recovered.result() == null
                    || (recovered.result().providerTransactionId() != null
                            && !confirm.flowId().equals(recovered.result().providerTransactionId()))
                    || provider.publicKey() == null || provider.publicKey().isBlank())
                return Optional.empty();
            return Optional.of(new Card(provider.publicKey(), recovered.clientSecret(), returnUrl(confirm)));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    /** Confirms PENDING (stale) and UNCONFIRMED sends by reading the Payment Flow. Never sends again. */
    void settle(UUID operationId) {
        for (Send send : sends.inFlight(operationId)) {
            if (send.status() == Status.PENDING && !sends.pendingOlderThan(send.id(), pendingAge)) continue;
            try {
                var flow = gateway.findFlow(send.flowId()).orElse(null);
                if (flow == null) continue;
                boolean applied = send.type() == SendType.FLOW_UPDATE
                        ? send.customerId().equals(flow.customerId()) && send.paymentMethodId().equals(flow.paymentMethodId())
                        : !"requires_confirmation".equals(flow.status());
                sends.finish(send.id(), send.status(), applied ? Status.SUCCEEDED : Status.FAILED, applied ? null : "not_applied");
            } catch (SavedCardGatewayException stillUnknown) {
                log.warn("Order change card send {} is still unconfirmed: {}", send.id(), stillUnknown.getMessage());
            }
        }
    }

    private List<Send> owned(long userId, long orderId, UUID admission) {
        if (admission == null) return List.of();
        return sends.forAdmission(admission).stream().filter(s -> s.userId() == userId && s.orderId() == orderId).toList();
    }

    private static Optional<Send> latestConfirm(List<Send> all) {
        return all.stream().filter(s -> s.type() == SendType.CONFIRM).reduce((a, b) -> b);
    }

    private String returnUrl(Send send) {
        return baseUrl + "/orders/" + send.orderId() + "/edit/" + send.admissionId() + "/payment/return";
    }

    /** Sends and records the result: SUCCEEDED, FAILED (PAY.JP refused) or UNCONFIRMED. */
    private Status send(Send send, Supplier<SavedCardGateway.FlowState> call, AtomicReference<SavedCardGateway.FlowState> response) {
        Status status;
        String code = null;
        try {
            var result = Objects.requireNonNull(call.get());
            if (response != null) response.set(result);
            status = Status.SUCCEEDED;
        } catch (SavedCardGatewayException failure) {
            status = failure.kind() == SavedCardGatewayException.Kind.REJECTED ? Status.FAILED : Status.UNCONFIRMED;
            code = failure.code();
            // A CONFIRM row records whether the confirm reached PAY.JP. A refused confirm (402 decline) did when the
            // Payment Flow left requires_confirmation; only a confirm that left it there was not delivered.
            if (status == Status.FAILED && send.type() == SendType.CONFIRM) status = delivered(send.flowId());
        } catch (IllegalArgumentException invalid) {
            status = Status.FAILED;
            code = "invalid_request";
        } catch (RuntimeException unexpected) {
            status = Status.UNCONFIRMED;
            code = "unexpected";
        }
        if (sends.finish(send.id(), Status.PENDING, status, code).isEmpty())
            log.warn("Order change card send {} was confirmed by another request first", send.id());
        return status;
    }

    private Status delivered(String flowId) {
        try {
            var flow = gateway.findFlow(flowId).orElse(null);
            if (flow == null) return Status.UNCONFIRMED;
            return "requires_confirmation".equals(flow.status()) ? Status.FAILED : Status.SUCCEEDED;
        } catch (SavedCardGatewayException unreadable) {
            return Status.UNCONFIRMED;
        }
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Saved card order change commands run outside a caller transaction");
    }
}
