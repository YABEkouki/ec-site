package com.example.ecsite.service.payment;

import java.util.*;
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
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final EntityManager em;
    private final OrderWriteContext context;
    private final OrderShipmentGuard guard;
    private final CaptureShipmentPolicy policy;
    private final OrderStatusHistoryService histories;

    public CaptureShipmentOperations(OrderRepository orders, PaymentRepository payments,
            PaymentTransactionRepository transactions, EntityManager em, OrderWriteContext context,
            OrderShipmentGuard guard, CaptureShipmentPolicy policy, OrderStatusHistoryService histories) {
        this.orders = orders; this.payments = payments; this.transactions = transactions; this.em = em;
        this.context = context; this.guard = guard; this.policy = policy; this.histories = histories;
    }

    public PaymentCaptureStart start(Long orderId, Long accountId, String username, String internalNote) {
        if (orderId == null || orderId <= 0) throw new IllegalArgumentException("正の注文IDが必要です。");
        AdminUserDetails actor = requireActor(accountId, username);
        context.requireCleanWritableReadCommitted();
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        context.refreshWritableOrder(order);
        if (order.getStatus() != OrderStatus.ORDERED && order.getStatus() != OrderStatus.SHIPPED) throw recovery(orderId);
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
        var now = orders.findCurrentTimeInTokyo();
        var capture = new PaymentTransaction(payment, PaymentTransactionType.CAPTURE, order.getTotalAmount(),
                order.getContentRevision(), UUID.randomUUID().toString(), now,
                new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN, actor.getId(), actor.getUsername(), internalNote));
        transactions.save(capture);
        order.confirmCardShipment(now, actor.getId(), actor.getUsername(), payment.getId());
        histories.record(order, OrderStatus.ORDERED, OrderStatus.SHIPPED, OrderStatusHistoryActorType.ADMIN,
                actor.getId(), actor.getUsername(), internalNote);
        return startResult(PaymentOperationSnapshot.from(capture), PaymentStartDisposition.NEW);
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
