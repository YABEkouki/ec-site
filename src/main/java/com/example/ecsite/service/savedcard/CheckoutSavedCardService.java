package com.example.ecsite.service.savedcard;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.ecsite.config.PayJpSafetyProperties;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.payment.savedcard.SavedCardGateway;
import com.example.ecsite.payment.savedcard.SavedCardGatewayException;
import com.example.ecsite.service.payment.PaymentAuthorizationService;
import com.example.ecsite.service.payment.PaymentSafetyStopException;
import com.example.ecsite.service.payment.PaymentService;
import com.example.ecsite.service.savedcard.CheckoutCardSendStore.Purpose;
import com.example.ecsite.service.savedcard.CheckoutCardSendStore.Send;
import com.example.ecsite.service.savedcard.CheckoutCardSendStore.SendType;
import com.example.ecsite.service.savedcard.CheckoutCardSendStore.Status;

/**
 * Feature139-B (docs/feature139/saved-cards.md ch.6): saved cards on the checkout Payment Flow.
 * This site only sends the update (customer / card) and the confirm; the authorization is still decided by reading
 * the Payment Flow through the existing {@link PaymentAuthorizationService#refreshAuthorization}. Sends are
 * recorded before and after, outside transactions; unknown results are confirmed by reading, never by resending.
 */
@Service
public class CheckoutSavedCardService {

    public enum PayOutcome {
        AUTHORIZED, REQUIRES_ACTION, DECLINED, CONFIRM_NOT_SENT, CARD_NOT_USABLE, LIMIT_REACHED, FAILED, PENDING
    }

    public enum RegisterOutcome { READY, LIMIT, FAILED }

    public enum CheckoutRegistration { NONE, REGISTERED, DUPLICATE, LIMIT, FAILED }

    private static final Logger log = LoggerFactory.getLogger(CheckoutSavedCardService.class);

    private final SavedCardService cards;
    private final SavedCardGateway gateway;
    private final CheckoutCardSendStore sends;
    private final PaymentAuthorizationService authorizations;
    private final PaymentService payments;
    private final Duration pendingAge;
    private final String returnUrl;

    public CheckoutSavedCardService(SavedCardService cards, SavedCardGateway gateway, CheckoutCardSendStore sends,
            PaymentAuthorizationService authorizations, PaymentService payments, PayJpSafetyProperties safety,
            @Value("${app.base-url}") String baseUrl) {
        this.cards = cards;
        this.gateway = gateway;
        this.sends = sends;
        this.authorizations = authorizations;
        this.payments = payments;
        this.pendingAge = safety.connectTimeout().plus(safety.readTimeout()).plusMinutes(1);
        this.returnUrl = baseUrl.replaceAll("/+$", "") + "/checkout/payment/return";
    }

    public boolean enabled() {
        return cards.enabled();
    }

    /** Saved cards for the payment page; empty when unavailable or PAY.JP cannot be read (new card entry only). */
    public Optional<SavedCardService.CheckoutCards> options(long userId) {
        if (!enabled()) return Optional.empty();
        try {
            return Optional.of(cards.cardsForCheckout(userId));
        } catch (SavedCardGatewayException | SavedCardService.UnavailableException failure) {
            log.warn("Saved cards unavailable on the payment page: {}", failure.getMessage());
            return Optional.empty();
        }
    }

    /** True when this site confirmed the authorization last, so a 3-D Secure action may be continued in the browser. */
    public boolean serverConfirmed(long transactionId) {
        List<Send> all = sends.forTransaction(transactionId);
        return !all.isEmpty() && all.getLast().type() == SendType.CONFIRM && all.getLast().status() != Status.FAILED;
    }

    /** True when a saved card update of this authorization succeeded (its confirm may be sent again). */
    public boolean savedCardSelected(long transactionId) {
        return sends.forTransaction(transactionId).stream().filter(s -> s.type() == SendType.FLOW_UPDATE)
                .reduce((a, b) -> b).filter(s -> s.purpose() == Purpose.SAVED_CARD && s.status() == Status.SUCCEEDED)
                .isPresent();
    }

    public PayOutcome pay(long userId, long orderId, long paymentId, long transactionId, String paymentMethodId) {
        if (!enabled() || !currentTarget(userId, orderId, paymentId, transactionId)) return PayOutcome.PENDING;
        settle(transactionId);
        if (!sends.inFlight(transactionId).isEmpty()) return PayOutcome.PENDING;
        long confirms = sends.forTransaction(transactionId).stream().filter(s -> s.type() == SendType.CONFIRM).count();
        if (confirms >= CheckoutCardSendStore.MAX_CONFIRMS) return PayOutcome.LIMIT_REACHED;
        try {
            cards.requireUsableCard(userId, paymentMethodId);
        } catch (SavedCardService.NotOwnedException notUsable) {
            return PayOutcome.CARD_NOT_USABLE;
        } catch (SavedCardGatewayException unreadable) {
            return PayOutcome.PENDING;
        }
        String customerId = cards.customerIdOf(userId);
        String flowId = payments.getProviderPaymentId(paymentId);

        Send update = beginPayment(userId, orderId, paymentId, transactionId, SendType.FLOW_UPDATE, flowId, customerId, paymentMethodId);
        if (update == null) return PayOutcome.PENDING;
        Status updated = send(update, () -> gateway.updateFlowCustomer(flowId, customerId, paymentMethodId, update.idempotencyKey()));
        if (updated == Status.SUCCEEDED) {
            Send confirm = beginPayment(userId, orderId, paymentId, transactionId, SendType.CONFIRM, flowId, customerId, paymentMethodId);
            if (confirm == null) return PayOutcome.PENDING;
            send(confirm, () -> gateway.confirmFlow(flowId, returnUrl, confirm.idempotencyKey()));
        }
        PayOutcome outcome = observe(orderId, paymentId);
        settle(transactionId);
        if (updated == Status.FAILED && (outcome == PayOutcome.DECLINED || outcome == PayOutcome.CONFIRM_NOT_SENT))
            return PayOutcome.CARD_NOT_USABLE; // PAY.JP refused the card for this Payment Flow
        return outcome;
    }

    /** Before the browser confirms a new card the user wants to register: set the customer on the Payment Flow. */
    public RegisterOutcome prepareRegistration(long userId, long orderId, long paymentId, long transactionId) {
        if (!enabled() || !currentTarget(userId, orderId, paymentId, transactionId)) return RegisterOutcome.FAILED;
        try {
            settle(transactionId);
            if (!sends.inFlight(transactionId).isEmpty()) return RegisterOutcome.FAILED;
            if (!cards.cardsForCheckout(userId).canRegister()) return RegisterOutcome.LIMIT;
            String customerId = cards.prepareCustomer(userId);
            String flowId = payments.getProviderPaymentId(paymentId);
            var latestUpdate = latestUpdate(transactionId);
            if (latestUpdate.filter(s -> s.purpose() == Purpose.REGISTER_NEW_CARD && s.status() == Status.SUCCEEDED
                    && customerId.equals(s.customerId())).isPresent()) return RegisterOutcome.READY;
            Send update = sends.begin(userId, orderId, paymentId, transactionId, SendType.FLOW_UPDATE,
                    Purpose.REGISTER_NEW_CARD, flowId, customerId, null);
            return send(update, () -> gateway.updateFlowCustomer(flowId, customerId, null, update.idempotencyKey()))
                    == Status.SUCCEEDED ? RegisterOutcome.READY : RegisterOutcome.FAILED;
        } catch (RuntimeException failure) {
            log.warn("Card registration could not be prepared for order {}: {}", orderId, failure.getMessage());
            return RegisterOutcome.FAILED;
        }
    }

    /**
     * After the authorization of the order succeeded (decision 6.10-2: only when the customer reaches completion).
     * Registers the card when the user asked for it on this authorization. Never affects the order.
     */
    public CheckoutRegistration completeRegistration(long userId, long orderId, long paymentId) {
        if (!enabled()) return CheckoutRegistration.NONE;
        try {
            var update = sends.forPayment(paymentId).stream()
                    .filter(s -> s.type() == SendType.FLOW_UPDATE && s.userId() == userId && s.orderId() == orderId)
                    .reduce((a, b) -> b)
                    .filter(s -> s.purpose() == Purpose.REGISTER_NEW_CARD && s.status() == Status.SUCCEEDED);
            if (update.isEmpty()) return CheckoutRegistration.NONE;
            var flow = gateway.findFlow(update.get().flowId()).orElse(null);
            if (flow == null || flow.paymentMethodId() == null
                    || !("requires_capture".equals(flow.status()) || "succeeded".equals(flow.status())))
                return CheckoutRegistration.FAILED;
            var result = cards.registerCheckoutCard(userId, flow.paymentMethodId());
            if (result.isEmpty()) return CheckoutRegistration.FAILED;
            return switch (result.get()) {
                case REGISTERED -> CheckoutRegistration.REGISTERED;
                case DUPLICATE -> CheckoutRegistration.DUPLICATE;
                case LIMIT -> CheckoutRegistration.LIMIT;
                default -> CheckoutRegistration.FAILED;
            };
        } catch (RuntimeException failure) {
            log.warn("Checkout card of order {} was not registered: {}", orderId, failure.getMessage());
            return CheckoutRegistration.FAILED;
        }
    }

    /** Confirms PENDING (stale) and UNCONFIRMED sends by reading the Payment Flow. Never sends again. */
    void settle(long transactionId) {
        for (Send send : sends.inFlight(transactionId)) {
            if (send.status() == Status.PENDING && !sends.pendingOlderThan(send.id(), pendingAge)) continue;
            try {
                var flow = gateway.findFlow(send.flowId()).orElse(null);
                if (flow == null) continue;
                boolean applied = send.type() == SendType.FLOW_UPDATE
                        ? send.customerId().equals(flow.customerId())
                                && (send.paymentMethodId() == null || send.paymentMethodId().equals(flow.paymentMethodId()))
                        : !"requires_confirmation".equals(flow.status());
                sends.finish(send.id(), send.status(), applied ? Status.SUCCEEDED : Status.FAILED, applied ? null : "not_applied");
            } catch (SavedCardGatewayException stillUnknown) {
                log.warn("Checkout card send {} is still unconfirmed: {}", send.id(), stillUnknown.getMessage());
            }
        }
    }

    private Optional<Send> latestUpdate(long transactionId) {
        return sends.forTransaction(transactionId).stream().filter(s -> s.type() == SendType.FLOW_UPDATE).reduce((a, b) -> b);
    }

    /** Null when V110 refused the send because the order started being closed as unpaid in the meantime (Feature142-A). */
    private Send beginPayment(long userId, long orderId, long paymentId, long transactionId, SendType type, String flowId,
            String customerId, String paymentMethodId) {
        try {
            return sends.begin(userId, orderId, paymentId, transactionId, type, Purpose.SAVED_CARD, flowId, customerId, paymentMethodId);
        } catch (org.springframework.dao.DataIntegrityViolationException refused) {
            if (currentTarget(userId, orderId, paymentId, transactionId)) throw refused;
            return null;
        }
    }

    private boolean currentTarget(long userId, long orderId, long paymentId, long transactionId) {
        return authorizations.checkoutTarget(orderId, paymentId, userId)
                .filter(t -> t.transactionId() == transactionId).isPresent();
    }

    private PayOutcome observe(long orderId, long paymentId) {
        final AuthorizationResult result;
        try {
            result = authorizations.refreshAuthorization(orderId, paymentId);
        } catch (PaymentGatewayException | PaymentSafetyStopException unknown) {
            return PayOutcome.PENDING;
        }
        return switch (result.status()) {
            case AUTHORIZED -> PayOutcome.AUTHORIZED;
            case REQUIRES_ACTION -> PayOutcome.REQUIRES_ACTION;
            case REQUIRES_PAYMENT_METHOD -> PayOutcome.DECLINED;
            case REQUIRES_CONFIRMATION -> PayOutcome.CONFIRM_NOT_SENT;
            case FAILED -> PayOutcome.FAILED;
            case PENDING -> PayOutcome.PENDING;
        };
    }

    /** Sends and records the result: SUCCEEDED, FAILED (PAY.JP refused) or UNCONFIRMED. */
    private Status send(Send send, Supplier<?> call) {
        Status status;
        String code = null;
        try {
            Objects.requireNonNull(call.get());
            status = Status.SUCCEEDED;
        } catch (SavedCardGatewayException failure) {
            status = failure.kind() == SavedCardGatewayException.Kind.REJECTED ? Status.FAILED : Status.UNCONFIRMED;
            code = failure.code();
        } catch (IllegalArgumentException invalid) {
            status = Status.FAILED;
            code = "invalid_request";
        } catch (RuntimeException unexpected) {
            status = Status.UNCONFIRMED;
            code = "unexpected";
        }
        if (sends.finish(send.id(), Status.PENDING, status, code).isEmpty())
            log.warn("Checkout card send {} was confirmed by another request first", send.id());
        return status;
    }
}
