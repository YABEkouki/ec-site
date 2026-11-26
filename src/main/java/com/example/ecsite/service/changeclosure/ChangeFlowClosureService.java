package com.example.ecsite.service.changeclosure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.payment.CancellationRequest;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayErrorCategory;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.payment.payjp.PayJpPaymentFlowObservation;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.Actor;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.Closure;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.ReleaseReason;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.Send;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.SendStatus;
import com.example.ecsite.service.changeclosure.ChangeFlowClosureStore.Status;

/**
 * Feature143-A/B: closes an order change whose re-authorization was never completed (docs/feature143/unfinished-change-closure.md).
 *
 * After automatic_until the change can never be adopted. The cancel also hits a Payment Flow that has just been
 * authorized, so the change is fenced (application and V111) from the request until the outcome is known. It is aborted,
 * its stock returned and its admission closed only by f143_close_unfinished, after the flow was read as canceled.
 * Every HTTP call is outside a transaction.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ChangeFlowClosureService {
    private static final Logger log = LoggerFactory.getLogger(ChangeFlowClosureService.class);

    /** AUTHORIZED: PAY.JP had already authorized the new card payment, so nothing was changed. */
    public enum Outcome { CLOSED, AUTHORIZED, PROCESSING, UNREADABLE, CHANGED, REJECTED, UNCONFIRMED }

    private enum FlowState { UNFINISHED, AUTHORIZED, PROCESSING, CANCELED }

    /** evidence is the reduced document V111 checks; no card data and no response body. */
    private record Observed(FlowState state, String evidence) {}

    /** Waits measured by the database clock. PAY.JP keeps an idempotency key for at least 24 hours. */
    public record Timing(Duration staleRequest, Duration sendAnswerWait, Duration keyLifetime, Duration errorRetryAfter) {
        public static final Timing DEFAULT = new Timing(Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofHours(23), Duration.ofMinutes(10));
    }
    static final int MAX_AUTOMATIC_SENDS = 5;
    /** PAY.JP answered that the Payment Flow is in a state that cannot be canceled. */
    private static final Set<String> REJECTIONS = Set.of("invalid_status", "payment_flow_not_cancelable");

    private final ChangeFlowClosureStore store;
    private final PaymentGateway gateway;
    private final Timing timing;

    @Autowired
    public ChangeFlowClosureService(ChangeFlowClosureStore store, PaymentGateway gateway) {
        this(store, gateway, Timing.DEFAULT);
    }

    public ChangeFlowClosureService(ChangeFlowClosureStore store, PaymentGateway gateway, Timing timing) {
        this.store = store;
        this.gateway = gateway;
        this.timing = timing;
    }

    /** Requests a closure, or continues the one whose cancel is still unresolved. */
    public Outcome close(long orderId, Actor actor) {
        var open = store.open(orderId);
        if (open.isPresent() && open.get().status() == Status.SENDING) return resume(open.get(), true);
        if (open.isPresent()) {
            if (!store.closureOlderThan(open.get().id(), timing.staleRequest()))
                throw new ChangeFlowClosureException("この注文の終了処理を実行中です。しばらくしてから画面を更新してください。");
            store.release(open.get().id(), ReleaseReason.STALE);
        }
        Closure closure = store.closableOperation(orderId).flatMap(operation -> store.request(orderId, operation, actor))
                .orElseThrow(() -> new ChangeFlowClosureException(
                        "この注文には、終了できる注文変更がありません。注文変更の状態と、一括編集の稼働状態（Feature125）をご確認ください。"));
        return logged(closure, start(closure));
    }

    private Outcome start(Closure closure) {
        Optional<Observed> observed = observe(closure);
        if (observed.isEmpty()) return released(closure, ReleaseReason.UNREADABLE, Outcome.UNREADABLE);
        switch (observed.get().state()) {
            case AUTHORIZED -> { return released(closure, ReleaseReason.AUTHORIZED, Outcome.AUTHORIZED); }
            case PROCESSING -> { return released(closure, ReleaseReason.PROCESSING, Outcome.PROCESSING); }
            case CANCELED -> { return apply(closure, observed.get()) ? Outcome.CLOSED : released(closure, ReleaseReason.CHANGED, Outcome.CHANGED); }
            case UNFINISHED -> { }
        }
        Optional<Send> send;
        try {
            send = store.startSending(closure);
        } catch (DataAccessException refused) {
            send = Optional.empty();
        }
        if (send.isEmpty()) return released(closure, ReleaseReason.CHANGED, Outcome.CHANGED);
        dispatch(closure, send.get());
        return settle(closure, observe(closure));
    }

    /**
     * Feature143-B: closes the changes left after the grace, oldest first. Only requests a closure; the cancel, its
     * application and every guard are those of the administrator's closure. One failure never stops the others.
     */
    public void closeDue(Duration after, Duration retryAfter, int limit) {
        for (ChangeFlowClosureStore.Due due : store.dueOperations(after, retryAfter, limit)) {
            try {
                store.requestDue(due, after, retryAfter).ifPresent(closure -> logged(closure, start(closure)));
            } catch (RuntimeException failure) {
                log.warn("Automatic order change flow closure deferred: orderId={}, error={}", due.orderId(), failure.getClass().getSimpleName());
            }
        }
    }

    /** Resolves interrupted closures. One failure never stops the others. */
    public void recover(int limit) {
        for (Closure closure : store.openClosures(limit)) {
            try {
                if (closure.status() == Status.SENDING) resume(closure, false);
                else if (store.closureOlderThan(closure.id(), timing.staleRequest())) store.release(closure.id(), ReleaseReason.STALE);
            } catch (RuntimeException failure) {
                log.warn("Order change flow closure deferred: closureId={}, orderId={}", closure.id(), closure.orderId());
            }
        }
    }

    private Outcome resume(Closure closure, boolean byAdministrator) {
        Optional<Send> latest = store.latestSend(closure.id());
        // A request whose answer may still arrive in another thread is left alone.
        if (latest.isPresent() && latest.get().status() == SendStatus.PENDING
                && !store.sendOlderThan(latest.get().id(), timing.sendAnswerWait())) return Outcome.UNCONFIRMED;
        Optional<Observed> observed = observe(closure);
        Outcome outcome = settle(closure, observed);
        if (outcome != Outcome.UNCONFIRMED || observed.isEmpty()
                || observed.get().state() == FlowState.PROCESSING) return logged(closure, outcome);
        Optional<Send> send = next(closure, latest, byAdministrator);
        if (send.isEmpty()) return outcome;
        dispatch(closure, send.get());
        return logged(closure, settle(closure, observe(closure)));
    }

    /** The same key while its answer is unknown; a new key only after an error answer or after the key's lifetime. */
    private Optional<Send> next(Closure closure, Optional<Send> latest, boolean byAdministrator) {
        if (latest.isEmpty()) return store.resend(closure);
        Send send = latest.get();
        return switch (send.status()) {
            case PENDING, UNCONFIRMED -> {
                if (!store.sendOlderThan(send.id(), timing.keyLifetime())) yield Optional.of(send);
                store.finishSend(send.id(), SendStatus.EXPIRED, null);
                yield store.resend(closure);
            }
            case EXPIRED -> store.resend(closure);
            case ERROR -> byAdministrator || (store.sends(closure.id()).size() < MAX_AUTOMATIC_SENDS
                    && store.sendOlderThan(send.id(), timing.errorRetryAfter())) ? store.resend(closure) : Optional.empty();
            case ACCEPTED, REJECTED -> Optional.empty();
        };
    }

    private void dispatch(Closure closure, Send send) {
        SendStatus status;
        String code = null;
        try {
            var result = gateway.cancelUnpaidCheckout(new CancellationRequest(closure.flowId(), send.idempotencyKey()));
            boolean canceled = result != null && result.status() == CancellationResultStatus.CANCELLED;
            status = canceled ? SendStatus.ACCEPTED : SendStatus.ERROR;
            if (!canceled) code = "unexpected_status";
        } catch (PaymentGatewayException failure) {
            code = failure.providerErrorCode();
            status = classify(failure);
        } catch (RuntimeException failure) {
            status = SendStatus.UNCONFIRMED;
        }
        store.finishSend(send.id(), status, code);
    }

    private static SendStatus classify(PaymentGatewayException failure) {
        Integer http = failure.httpStatus();
        if (http == null) return SendStatus.UNCONFIRMED;
        if (http == 400 && failure.providerErrorCode() != null && REJECTIONS.contains(failure.providerErrorCode()))
            return SendStatus.REJECTED;
        if (http == 429 || http == 502 || http == 503 || http == 504
                || failure.category() == PaymentGatewayErrorCategory.IDEMPOTENCY_WAIT_TIMEOUT) return SendStatus.UNCONFIRMED;
        return SendStatus.ERROR;
    }

    private Outcome settle(Closure closure, Optional<Observed> observed) {
        if (observed.isEmpty()) return Outcome.UNCONFIRMED;
        if (observed.get().state() == FlowState.CANCELED) return apply(closure, observed.get()) ? Outcome.CLOSED : Outcome.UNCONFIRMED;
        boolean rejected = store.latestSend(closure.id()).filter(s -> s.status() == SendStatus.REJECTED).isPresent();
        return rejected && store.reject(closure.id()) ? Outcome.REJECTED : Outcome.UNCONFIRMED;
    }

    /** Empty unless the response is readable and belongs to exactly this flow. */
    private Optional<Observed> observe(Closure closure) {
        try {
            PayJpPaymentFlowObservation flow = gateway.observeOrderChangeFlow(closure.flowId());
            if (flow == null || !closure.flowId().equals(flow.id()) || flow.status() == null) return Optional.empty();
            FlowState state = switch (flow.status()) {
                case "requires_payment_method", "requires_confirmation", "requires_action" -> FlowState.UNFINISHED;
                case "requires_capture" -> FlowState.AUTHORIZED;
                case "processing" -> FlowState.PROCESSING;
                case "canceled" -> FlowState.CANCELED;
                default -> null;
            };
            return state == null ? Optional.empty() : Optional.of(new Observed(state, evidence(flow)));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static String evidence(PayJpPaymentFlowObservation flow) {
        var metadata = flow.metadata() == null ? java.util.Map.<String, Object>of() : flow.metadata();
        var document = new LinkedHashMap<String, Object>();
        document.put("flow", flow.id());
        document.put("status", flow.status());
        document.put("amount", flow.amount());
        document.put("amountReceived", flow.amountReceived());
        document.put("amountCapturable", flow.amountCapturable());
        document.put("currency", flow.currency());
        document.put("captureMethod", flow.captureMethod());
        document.put("livemode", flow.livemode());
        document.put("order", metadata.get("order_id"));
        document.put("operation", metadata.get("order_change_operation_id"));
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(document);
    }

    /** False when V111 refuses the evidence or the change is no longer the one that was requested. */
    private boolean apply(Closure closure, Observed observed) {
        try {
            store.closeUnfinished(closure, observed.evidence());
            return true;
        } catch (DataAccessException refused) {
            log.warn("Unfinished order change was not closed: closureId={}, orderId={}", closure.id(), closure.orderId());
            return store.closed(closure.id());
        }
    }

    private Outcome released(Closure closure, ReleaseReason reason, Outcome outcome) {
        store.release(closure.id(), reason);
        return outcome;
    }

    private static Outcome logged(Closure closure, Outcome outcome) {
        log.info("Order change flow closure: outcome={}, closureId={}, orderId={}, paymentId={}", outcome, closure.id(),
                closure.orderId(), closure.paymentId());
        return outcome;
    }
}
