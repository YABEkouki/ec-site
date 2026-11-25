package com.example.ecsite.service.unpaidclosure;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.CancellationRequest;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayErrorCategory;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.service.payment.PaymentAuthorizationResultService;
import com.example.ecsite.service.payment.PaymentOperationSnapshotService;
import com.example.ecsite.service.payment.PaymentResultApplication;
import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore.Actor;
import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore.Closure;
import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore.ReleaseReason;
import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore.Send;
import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore.SendStatus;
import com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore.Status;

/**
 * Feature142-A: closes an order whose checkout payment was never completed (docs/feature142/unpaid-order-closure.md).
 *
 * The cancel also hits a Payment Flow that has just been authorized, so acceptance is fenced (application and V110)
 * from the request until the outcome is known. The order is cancelled and its stock returned only by the existing
 * application of a Payment Flow that was read as canceled. Every HTTP call is outside a transaction.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class UnpaidOrderClosureService {
    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderClosureService.class);

    /** ACCEPTED: the authorization had already succeeded, so the order was formally accepted instead. */
    public enum Outcome { CLOSED, ACCEPTED, PROCESSING, UNREADABLE, CHANGED, REJECTED, UNCONFIRMED }

    /** Waits measured by the database clock. PAY.JP keeps an idempotency key for at least 24 hours. */
    record Timing(Duration staleRequest, Duration sendAnswerWait, Duration keyLifetime, Duration errorRetryAfter) {
        static final Timing DEFAULT = new Timing(Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofHours(23), Duration.ofMinutes(10));
    }
    static final int MAX_AUTOMATIC_SENDS = 5;
    /** PAY.JP answered that the Payment Flow is in a state that cannot be canceled. */
    private static final Set<String> REJECTIONS = Set.of("invalid_status", "payment_flow_not_cancelable");

    private final UnpaidOrderClosureStore store;
    private final PaymentGateway gateway;
    private final PaymentOperationSnapshotService snapshots;
    private final PaymentAuthorizationResultService results;
    private final Timing timing;

    @Autowired
    public UnpaidOrderClosureService(UnpaidOrderClosureStore store, PaymentGateway gateway,
            PaymentOperationSnapshotService snapshots, PaymentAuthorizationResultService results) {
        this(store, gateway, snapshots, results, Timing.DEFAULT);
    }

    UnpaidOrderClosureService(UnpaidOrderClosureStore store, PaymentGateway gateway,
            PaymentOperationSnapshotService snapshots, PaymentAuthorizationResultService results, Timing timing) {
        this.timing = timing;
        this.store = store;
        this.gateway = gateway;
        this.snapshots = snapshots;
        this.results = results;
    }

    /** Requests a closure, or continues the one whose cancel is still unresolved. */
    public Outcome close(long orderId, Actor actor) {
        var open = store.open(orderId);
        if (open.isPresent() && open.get().status() == Status.SENDING) return resume(open.get(), true);
        if (open.isPresent()) {
            if (!store.closureOlderThan(open.get().id(), timing.staleRequest()))
                throw new UnpaidOrderClosureException("この注文の終了処理を実行中です。しばらくしてから画面を更新してください。");
            store.release(open.get().id(), ReleaseReason.STALE);
        }
        Closure closure = store.request(orderId, actor).orElseThrow(() -> new UnpaidOrderClosureException(
                "この注文は、決済未完了の注文として終了できません。注文・決済の状態をご確認ください（注文から60分以上たっている必要があります）。"));
        return logged(closure, start(closure));
    }

    private Outcome start(Closure closure) {
        Optional<AuthorizationResult> observed = observe(closure);
        if (observed.isEmpty()) return released(closure, ReleaseReason.UNREADABLE, Outcome.UNREADABLE);
        switch (observed.get().status()) {
            case AUTHORIZED -> {
                if (!store.release(closure.id(), ReleaseReason.AUTHORIZED)) return Outcome.CHANGED;
                return apply(closure, observed.get()) ? Outcome.ACCEPTED : Outcome.CHANGED;
            }
            case PENDING -> { return released(closure, ReleaseReason.PROCESSING, Outcome.PROCESSING); }
            case FAILED -> { return apply(closure, observed.get()) ? Outcome.CLOSED : released(closure, ReleaseReason.CHANGED, Outcome.CHANGED); }
            case REQUIRES_PAYMENT_METHOD, REQUIRES_CONFIRMATION, REQUIRES_ACTION -> { }
        }
        Optional<Send> send = store.startSending(closure);
        if (send.isEmpty()) return released(closure, ReleaseReason.CHANGED, Outcome.CHANGED);
        dispatch(closure, send.get());
        return settle(closure, observe(closure));
    }

    /** Resolves interrupted closures. One failure never stops the others. */
    public void recover(int limit) {
        for (Closure closure : store.openClosures(limit)) {
            try {
                if (closure.status() == Status.SENDING) resume(closure, false);
                else if (store.closureOlderThan(closure.id(), timing.staleRequest())) store.release(closure.id(), ReleaseReason.STALE);
            } catch (RuntimeException failure) {
                log.warn("Unpaid order closure deferred: closureId={}, orderId={}", closure.id(), closure.orderId());
            }
        }
    }

    private Outcome resume(Closure closure, boolean byAdministrator) {
        Optional<Send> latest = store.latestSend(closure.id());
        // A request whose answer may still arrive in another thread is left alone.
        if (latest.isPresent() && latest.get().status() == SendStatus.PENDING
                && !store.sendOlderThan(latest.get().id(), timing.sendAnswerWait())) return Outcome.UNCONFIRMED;
        Optional<AuthorizationResult> observed = observe(closure);
        Outcome outcome = settle(closure, observed);
        if (outcome != Outcome.UNCONFIRMED || observed.isEmpty()
                || observed.get().status() == AuthorizationResultStatus.PENDING) return logged(closure, outcome);
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

    private Outcome settle(Closure closure, Optional<AuthorizationResult> observed) {
        if (observed.isEmpty()) return Outcome.UNCONFIRMED;
        var status = observed.get().status();
        if (status == AuthorizationResultStatus.FAILED)
            return apply(closure, observed.get()) ? Outcome.CLOSED : Outcome.UNCONFIRMED;
        boolean rejected = store.latestSend(closure.id()).filter(s -> s.status() == SendStatus.REJECTED).isPresent();
        if (!rejected || !store.reject(closure.id())) return Outcome.UNCONFIRMED;
        if (status != AuthorizationResultStatus.AUTHORIZED) return Outcome.REJECTED;
        return apply(closure, observed.get()) ? Outcome.ACCEPTED : Outcome.REJECTED;
    }

    private Optional<AuthorizationResult> observe(Closure closure) {
        try {
            var recovery = gateway.retrieveAuthorization(closure.flowId());
            return recovery == null ? Optional.empty() : Optional.ofNullable(recovery.result());
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private boolean apply(Closure closure, AuthorizationResult result) {
        var applied = results.apply(snapshots.authorization(closure.orderId(), closure.paymentId()), result);
        return applied == PaymentResultApplication.APPLIED || applied == PaymentResultApplication.ALREADY_APPLIED
                // The reconciliation or a webhook may have applied the same canceled Payment Flow first.
                || result.status() == AuthorizationResultStatus.FAILED && store.closed(closure.orderId());
    }

    private Outcome released(Closure closure, ReleaseReason reason, Outcome outcome) {
        store.release(closure.id(), reason);
        return outcome;
    }

    private static Outcome logged(Closure closure, Outcome outcome) {
        log.info("Unpaid order closure: outcome={}, closureId={}, orderId={}, paymentId={}", outcome, closure.id(),
                closure.orderId(), closure.paymentId());
        return outcome;
    }
}
