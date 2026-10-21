package com.example.ecsite.service.payment;

import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import com.example.ecsite.config.*;
import com.example.ecsite.payment.capture.*;
import jakarta.persistence.EntityManager;
import com.example.ecsite.entity.*;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared, transaction-bound shipment operation for synchronous, webhook and reconciliation callers. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CaptureShipmentOperations {
    private final OrderPaymentGuard paymentGuard;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final EntityManager em;
    private final OrderWriteContext context;
    private final OrderShipmentGuard guard;
    private final CaptureShipmentPolicy policy;
    private final OrderStatusHistoryService histories;

    private final CaptureRequestStore requests;
    private final CaptureConnectionProperties connection;
    private final PayJpProperties activeConnection;
    private final PayJpSafetyProperties safety;
    private final OrderStatusHistoryRepository shipmentHistory;
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    public CaptureShipmentOperations(OrderRepository orders, PaymentRepository payments,
            PaymentTransactionRepository transactions, EntityManager em, OrderWriteContext context,
            OrderShipmentGuard guard, CaptureShipmentPolicy policy, OrderStatusHistoryService histories,
            CaptureRequestStore requests, CaptureConnectionProperties connection, PayJpProperties activeConnection,
            PayJpSafetyProperties safety, OrderStatusHistoryRepository shipmentHistory, OrderPaymentGuard paymentGuard) {
        this.paymentGuard=paymentGuard;
        this.orders = orders; this.payments = payments; this.transactions = transactions; this.em = em;
        this.context = context; this.guard = guard; this.policy = policy; this.histories = histories;
        this.requests = requests; this.connection = connection; this.activeConnection = activeConnection;
        this.safety = safety; this.shipmentHistory = shipmentHistory;
    }

    public PaymentCaptureStart start(Long orderId, Long accountId, String username, String internalNote) {
        if (orderId == null || orderId <= 0) throw new IllegalArgumentException("正の注文IDが必要です。");
        AdminUserDetails actor = requireActor(accountId, username);
        context.requireCleanWritableReadCommitted();
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        context.refreshWritableOrder(order);
        if (order.getStatus() != OrderStatus.ORDERED && order.getStatus() != OrderStatus.SHIPPED) throw recovery(orderId);
        if (order.getStatus()==OrderStatus.ORDERED) paymentGuard.requireNoOrderChange(order);
        guard.requirePrepared(order);
        LockedData data = load(order, null);
        PaymentOperationSnapshot saved = null;
        if (order.getStatus() == OrderStatus.SHIPPED) {
            var candidates = data.history().stream().filter(t -> t.getTransactionType() == PaymentTransactionType.CAPTURE
                    && Objects.equals(t.getPayment().getId(), order.getShipmentCapturePaymentId())).toList();
            if (candidates.size() != 1) throw recovery(orderId);
            saved = PaymentOperationSnapshot.from(candidates.getFirst());
        }
        var decision = policy.classify(order, data.payments(), data.history(), saved);
        if (decision.disposition() == CaptureShipmentPolicy.Disposition.RECOVERY_REQUIRED) throw recovery(orderId);
        if (decision.disposition() != CaptureShipmentPolicy.Disposition.NEW) {
            return startResult(saved, decision.disposition() == CaptureShipmentPolicy.Disposition.CONTINUE
                    ? PaymentStartDisposition.REUSED : PaymentStartDisposition.ALREADY_APPLIED);
        }
        Payment payment = data.payments().stream().filter(p -> p.getId().equals(decision.paymentId())).findFirst().orElseThrow();
        var identity = connection.requireIdentity(activeConnection);
        if (!safety.isValid()) throw recovery(orderId);
        var now = orders.findCurrentTimeInTokyo();
        var capture = new PaymentTransaction(payment, PaymentTransactionType.CAPTURE, order.getTotalAmount(),
                order.getContentRevision(), UUID.randomUUID().toString(), now,
                new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN, actor.getId(), actor.getUsername(), internalNote), PaymentTransactionPurpose.SHIPMENT_CAPTURE);
        transactions.saveAndFlush(capture);
        var created = now.atZone(JST).toInstant();
        var http = CaptureHttpRequest.create(identity, payment.getProviderPaymentId(), capture.getAmount(), capture.getIdempotencyKey());
        var stored = requests.insert(payment, capture, http, created,
                created.plus(safety.postMaxAge()).truncatedTo(ChronoUnit.MICROS));
        order.confirmCardShipment(now, actor.getId(), actor.getUsername(), payment.getId());
        histories.record(order, OrderStatus.ORDERED, OrderStatus.SHIPPED, OrderStatusHistoryActorType.ADMIN,
                actor.getId(), actor.getUsername(), internalNote);
        var snapshot = PaymentOperationSnapshot.from(capture);
        return new PaymentCaptureStart(snapshot.paymentId(), snapshot.transactionId(), snapshot.providerPaymentId(),
                snapshot.amount(), snapshot.idempotencyKey(), PaymentStartDisposition.NEW, snapshot,
                new InitialPost(stored.id(), snapshot));
    }

    /** In-memory capability issued only by the NEW branch; never reconstructed from persisted PENDING. */
    public static final class InitialPost {
        private final long requestId;
        private final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        private final PaymentOperationSnapshot snapshot;
        private InitialPost(long requestId, PaymentOperationSnapshot snapshot) {
            this.requestId = requestId; this.snapshot = snapshot;
        }
        public long requestId() { return requestId; }
        public PaymentOperationSnapshot snapshot() { return snapshot; }
    }

    public CaptureInitialPostPreparationService.Prepared prepareInitialPost(InitialPost initial) {
        if (initial == null) throw new IllegalArgumentException("Initial POST capability required");
        if (!initial.claimed.compareAndSet(false, true)) throw new IllegalStateException("Initial POST capability was already consumed");
        var snapshot = initial.snapshot();
        context.requireCleanWritableReadCommitted();
        Order order = orders.findByIdForUpdate(snapshot.orderId()).orElseThrow(() -> new OrderNotFoundException(snapshot.orderId()));
        context.refreshWritableOrder(order);
        guard.requirePrepared(order);
        var data = load(order, snapshot);
        var decision = policy.classify(order, data.payments(), data.history(), snapshot);
        if (decision.disposition() == CaptureShipmentPolicy.Disposition.ALREADY_APPLIED)
            return new CaptureInitialPostPreparationService.AlreadyApplied();
        if (decision.disposition() != CaptureShipmentPolicy.Disposition.CONTINUE) throw recovery(order.getId());
        var payment = data.payments().stream().filter(p -> p.getId().equals(snapshot.paymentId())).findFirst().orElseThrow();
        var capture = data.history().stream().filter(t -> t.getId().equals(snapshot.transactionId())).findFirst().orElseThrow();
        var identity = connection.requireIdentity(activeConnection);
        var stored = requests.findByIdAndCaptureTransactionId(initial.requestId(), snapshot.transactionId()).orElseThrow(() -> recovery(order.getId()));
        if (stored.id() != initial.requestId()) throw recovery(order.getId());
        stored.validateAgainst(payment, capture, identity);
        var shipments = shipmentHistory.findByOrderIdOrderByChangedAtAscIdAsc(order.getId()).stream()
                .peek(em::refresh).filter(h -> h.getToStatus() == OrderStatus.SHIPPED).toList();
        if (shipments.size() != 1) throw recovery(order.getId());
        var history = shipments.getFirst();
        if (history.getFromStatus() != OrderStatus.ORDERED || history.getChangedByType() != OrderStatusHistoryActorType.ADMIN
                || !Objects.equals(history.getChangedByAccountId(), order.getShippedByAccountId())
                || !Objects.equals(history.getChangedByUsername(), order.getShippedByUsername())
                || !Objects.equals(history.getInternalNote(), snapshot.internalNote())) throw recovery(order.getId());
        if (!safety.isValid()) throw recovery(order.getId());
        var created = capture.getCreatedAt().atZone(JST).toInstant();
        if (!created.equals(stored.createdAt())) throw recovery(order.getId());
        var deadline = stored.replayNotAfter();
        var currentLimit = created.plus(safety.postMaxAge());
        if (currentLimit.isBefore(deadline)) deadline = currentLimit;
        var now = orders.findCurrentTimeInTokyo().atZone(JST).toInstant();
        var ready = new CaptureInitialPostPreparationService.Ready(stored.httpRequest(), created, deadline);
        ready.requireWithinWindow(now);
        return ready;
    }

    /** Requery never calls start(): ORDERED cannot create an operation through this entry. */
    public PaymentCaptureStart prepareRequery(Long orderId) {
        return resolveRequery(orderId, null);
    }

    /** Check the original immutable target against the latest state after GET. */
    public PaymentCaptureStart confirmRequery(PaymentOperationSnapshot expected) {
        if (expected == null || expected.type() != PaymentTransactionType.CAPTURE) throw new CaptureRequeryRejectedException();
        return resolveRequery(expected.orderId(), expected);
    }

    private PaymentCaptureStart resolveRequery(Long orderId, PaymentOperationSnapshot expected) {
        if (orderId == null || orderId <= 0) throw new IllegalArgumentException("正の注文IDが必要です。");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AdminUserDetails actor))
            throw new AccessDeniedException("有効な管理者認証が必要です。");
        requireActor(actor.getId(), actor.getUsername());
        context.requireCleanWritableReadCommitted();
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        context.refreshWritableOrder(order);
        if (order.getStatus() != OrderStatus.SHIPPED || order.getShipmentCapturePaymentId() == null)
            throw new CaptureRequeryRejectedException();
        guard.requirePrepared(order);
        LockedData data = load(order, expected);
        var captures = data.history().stream().filter(t -> t.getTransactionType() == PaymentTransactionType.CAPTURE
                && Objects.equals(t.getPayment().getId(), order.getShipmentCapturePaymentId())).toList();
        if (captures.size() != 1) throw new CaptureRequeryRejectedException();
        var snapshot = expected == null ? PaymentOperationSnapshot.from(captures.getFirst()) : expected;
        var decision = policy.classify(order, data.payments(), data.history(), snapshot);
        if (decision.disposition() == CaptureShipmentPolicy.Disposition.ALREADY_APPLIED)
            return startResult(snapshot, PaymentStartDisposition.ALREADY_APPLIED);
        if (decision.disposition() != CaptureShipmentPolicy.Disposition.CONTINUE) throw new CaptureRequeryRejectedException();
        return startResult(snapshot, PaymentStartDisposition.REUSED);
    }

    public PaymentResultApplication apply(PaymentOperationSnapshot snapshot, CaptureResult result) {
        if (snapshot == null || snapshot.orderId() == null || result == null || result.status() == null)
            return PaymentResultApplication.CONFLICT;
        context.requireCleanWritableReadCommitted();
        Order order = orders.findByIdForUpdate(snapshot.orderId()).orElseThrow(() -> new OrderNotFoundException(snapshot.orderId()));
        context.refreshWritableOrder(order);
        if (order.getStatus() != OrderStatus.SHIPPED || !order.hasCompleteShippingPreparation()
                || !Objects.equals(order.getShipmentCapturePaymentId(), snapshot.paymentId())) return PaymentResultApplication.CONFLICT;
        LockedData data = load(order, snapshot);
        var target = data.history().stream().filter(t -> Objects.equals(t.getId(), snapshot.transactionId())).findFirst().orElse(null);
        var targetPayment = data.payments().stream().filter(p -> Objects.equals(p.getId(), snapshot.paymentId())).findFirst().orElse(null);
        if (!new PaymentPurposeResolver().matches(order, targetPayment, target, snapshot, data.payments(), data.history()))
            return PaymentResultApplication.CONFLICT;
        var decision = policy.classify(order, data.payments(), data.history(), snapshot);
        if (!Objects.equals(snapshot.providerPaymentId(), result.providerTransactionId())) return PaymentResultApplication.CONFLICT;
        if (decision.disposition() == CaptureShipmentPolicy.Disposition.ALREADY_APPLIED)
            return result.status() == CaptureResultStatus.CAPTURED
                    ? PaymentResultApplication.ALREADY_APPLIED : PaymentResultApplication.IGNORED_STALE;
        if (decision.disposition() != CaptureShipmentPolicy.Disposition.CONTINUE) return PaymentResultApplication.CONFLICT;
        Payment payment = data.payments().stream().filter(p -> p.getId().equals(decision.paymentId())).findFirst().orElseThrow();
        PaymentTransaction capture = data.history().stream().filter(t -> t.getId().equals(decision.captureTransactionId())).findFirst().orElseThrow();
        if (result.status() == CaptureResultStatus.PENDING) return PaymentResultApplication.APPLIED;
        var now = orders.findCurrentTimeInTokyo();
        if (result.status() == CaptureResultStatus.CAPTURED) {
            // Validate the Order timestamp contract before mutating Payment or its operation.
            order.recordShipmentCaptureSucceeded(payment.getId(), now);
            payment.markCaptured(now);
            capture.markSucceeded(result.providerTransactionId(), now);
        } else if (result.status() == CaptureResultStatus.FAILED) {
            capture.markFailed(result.providerTransactionId(), result.failureCode(), result.failureMessage(), now);
        }
        return PaymentResultApplication.APPLIED;
    }

    /** Parent Order lock serializes compliant operation writers. All Payments lock in repository ID order. */
    private LockedData load(Order order, PaymentOperationSnapshot target) {
        var current = payments.findByOrderIdForUpdate(order.getId());
        var history = new ArrayList<PaymentTransaction>();
        // Refresh writable result targets BEFORE any other refresh can discard their state.
        Payment targetPayment = target == null ? null : current.stream().filter(p -> Objects.equals(p.getId(), target.paymentId())).findFirst().orElse(null);
        PaymentTransaction targetTransaction = null;
        if (targetPayment != null) {
            var candidates = transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(targetPayment.getId());
            targetTransaction = candidates.stream().filter(t -> Objects.equals(t.getId(), target.transactionId())).findFirst().orElse(null);
            if (targetTransaction != null) context.refreshWritableCaptureTargets(targetPayment, targetTransaction);
        }
        for (Payment payment : current) if (payment != targetPayment || targetTransaction == null) em.refresh(payment);
        for (Payment payment : current) {
            for (PaymentTransaction transaction : transactions.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId())) {
                if (transaction != targetTransaction) em.refresh(transaction);
                history.add(transaction);
            }
        }
        return new LockedData(current, history);
    }

    private PaymentCaptureStart startResult(PaymentOperationSnapshot snapshot, PaymentStartDisposition disposition) {
        return new PaymentCaptureStart(snapshot.paymentId(), snapshot.transactionId(), snapshot.providerPaymentId(),
                snapshot.amount(), snapshot.idempotencyKey(), disposition, snapshot);
    }
    private IllegalStateException recovery(Long orderId) {
        return new IllegalStateException("売上確定対象を安全に確認できません。管理者による確認が必要です。注文番号: " + orderId);
    }
    private AdminUserDetails requireActor(Long id, String username) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))
                || !(auth.getPrincipal() instanceof AdminUserDetails actor) || actor.getId() == null || actor.getId() <= 0
                || actor.getUsername() == null || actor.getUsername().isBlank() || actor.getUsername().length() > 100
                || !Objects.equals(id, actor.getId()) || !Objects.equals(username, actor.getUsername()))
            throw new AccessDeniedException("有効な管理者認証が必要です。");
        return actor;
    }
    private record LockedData(List<Payment> payments, List<PaymentTransaction> history) {}
}
