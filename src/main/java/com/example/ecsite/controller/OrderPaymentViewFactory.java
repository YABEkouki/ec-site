package com.example.ecsite.controller;

import java.time.*;
import java.util.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.service.OrderModificationPolicy;
import com.example.ecsite.service.payment.*;
import org.springframework.stereotype.Component;

/** Advisory display only: no locks, persistence mutations, clock queries or Gateway access. */
@Component
public class OrderPaymentViewFactory {
    private final CaptureShipmentPolicy capturePolicy;
    private final OrderModificationPolicy modificationPolicy;
    private final Clock clock;
    private final PaymentConsistencyEvaluator consistency;

    public OrderPaymentViewFactory(CaptureShipmentPolicy capturePolicy, OrderModificationPolicy modificationPolicy, Clock clock, PaymentConsistencyEvaluator consistency) {
        this.consistency = consistency;
        this.capturePolicy = capturePolicy;
        this.modificationPolicy = modificationPolicy;
        this.clock = clock;
    }

    public enum State {
        AUTHORIZED("与信済み"), CAPTURE_PENDING("売上確定確認中"), CAPTURED("売上確定済み"),
        CAPTURE_FAILED("決済要確認"), REVIEW("決済情報要確認"), MANUAL("手動決済（支払済み）"),
        UNRECORDED("決済情報未登録"), PROCESSING("決済処理中"),
        LEGACY_PAID("支払記録あり（既存データ・決済方式要確認）"), LEGACY_REVIEW("既存の決済情報要確認");
        private final String message;
        State(String message) { this.message = message; }
    }
    public record View(State state, boolean canCapture, boolean canManualShip, boolean canCancel, boolean canMarkPaid, String recordedPaymentStatus) {
        public View(State state, boolean canCapture, boolean canManualShip, boolean canCancel, boolean canMarkPaid) {
            this(state, canCapture, canManualShip, canCancel, canMarkPaid, null);
        }
        public boolean canRequery() { return state == State.CAPTURE_PENDING; }
        public String message() { return state.message; }
        public boolean requiresAttention() { return state == State.REVIEW || state == State.CAPTURE_FAILED || state == State.LEGACY_REVIEW; }
    }
    public static View unavailable() { return new View(State.REVIEW, false, false, false, false); }

    public View create(Order order, List<Payment> payments, List<PaymentTransaction> history) {
        return create(order,payments,history,Set.of(),Set.of());
    }

    public View create(Order order, List<Payment> payments, List<PaymentTransaction> history,
            Set<UUID> completedChanges, Set<UUID> abortedChanges) {
        if (!validData(order, payments, history)) return unavailable();
        boolean unprepared = order.getShippingPreparationStartedAt() == null
                && order.getShippingPreparationStartedByAccountId() == null && order.getShippingPreparationStartedByUsername() == null;
        if (!unprepared && !order.hasCompleteShippingPreparation()) return unavailable();
        List<Payment> active = payments.stream().filter(p -> p.getStatus() != PaymentStatus.FAILED && p.getStatus() != PaymentStatus.CANCELLED).toList();
        boolean pending = history.stream().anyMatch(t -> t.getStatus() == PaymentTransactionStatus.PENDING);
        boolean local = active.isEmpty() && !pending;
        boolean cancellable = order.getStatus() == OrderStatus.ORDERED && !pending
                && modificationPolicy.allowsModification(order, LocalDateTime.now(clock));
        if (active.size() > 1) return unavailable();
        Payment card = active.isEmpty() ? null : active.getFirst();
        boolean authorizedCard = card != null && card.getProvider() == PaymentProvider.PAYJP
                && card.getPaymentMethod() == PaymentMethod.CARD && card.getStatus() == PaymentStatus.AUTHORIZED
                && card.getProviderPaymentId() != null && !card.getProviderPaymentId().isBlank();
        cancellable &= local || authorizedCard;

        if (order.getStatus() == OrderStatus.SHIPPED && order.getShipmentCapturePaymentId() == null && unprepared) {
            // No automatic legacy conversion, no inference of card capture from OrderStatus or paidAt.
            return new View(order.getPaidAt() != null && payments.isEmpty() ? State.LEGACY_PAID : State.LEGACY_REVIEW, false, false, false, false, card == null ? null : card.getStatus().name());
        }
        if (order.getStatus() == OrderStatus.PAID && (order.getShippedAt() != null
                || order.getShippedByAccountId() != null || order.getShippedByUsername() != null)) return unavailable();
        if (order.getStatus() == OrderStatus.SHIPPED && !unprepared && (order.getShippedAt() == null
                || order.getShippedByAccountId() == null || order.getShippedByAccountId() <= 0
                || order.getShippedByUsername() == null || order.getShippedByUsername().isBlank()
                || order.getShippedByUsername().length() > 100)) return unavailable();
        if (local && (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.SHIPPED)
                && order.getShipmentCapturePaymentId() == null && order.getPaidAt() != null
                && history.stream().noneMatch(t -> t.getTransactionType() == PaymentTransactionType.CAPTURE)) {
            return new View(State.MANUAL, false, order.getStatus() == OrderStatus.PAID && order.hasCompleteShippingPreparation(), false, false);
        }
        if (order.getStatus() == OrderStatus.ORDERED) {
            if (order.getShipmentCapturePaymentId() != null || order.getShippedAt() != null || order.getShippedByAccountId() != null || order.getShippedByUsername() != null || order.getPaidAt() != null) return unavailable();
            if (authorizedCard) {
                var authorizations = history.stream().filter(t -> Objects.equals(t.getPayment().getId(), card.getId())
                        && t.getTransactionType() == PaymentTransactionType.AUTHORIZE && t.getStatus() == PaymentTransactionStatus.SUCCEEDED).toList();
                if (authorizations.size() != 1 || !Objects.equals(authorizations.getFirst().getProviderTransactionId(), card.getProviderPaymentId())
                        || !consistency.evaluate(order, card, authorizations.getFirst()).canCapture()
                        || history.stream().anyMatch(t -> t.getTransactionType() == PaymentTransactionType.CAPTURE)) return unavailable();
                var decision = capturePolicy.classify(order, payments, history, null, completedChanges, abortedChanges);
                return new View(State.AUTHORIZED, decision.disposition() == CaptureShipmentPolicy.Disposition.NEW, false, cancellable, false);
            }
            if (local) return new View(State.UNRECORDED, false, false, cancellable, true);
            if (card != null && card.getProvider() == PaymentProvider.PAYJP && card.getPaymentMethod() == PaymentMethod.CARD
                    && (card.getStatus() == PaymentStatus.PENDING || card.getStatus() == PaymentStatus.REQUIRES_ACTION))
                return new View(State.PROCESSING, false, false, false, false);
            return unavailable();
        }
        if (order.getStatus() != OrderStatus.SHIPPED || card == null) return unavailable();
        var captures = history.stream().filter(t -> t.getTransactionType() == PaymentTransactionType.CAPTURE
                && Objects.equals(t.getPayment().getId(), order.getShipmentCapturePaymentId())).toList();
        if (captures.size() != 1) return unavailable();
        PaymentTransaction capture = captures.getFirst();
        var decision = capturePolicy.classify(order, payments, history, snapshot(capture), completedChanges, abortedChanges);
        if (decision.disposition() == CaptureShipmentPolicy.Disposition.CONTINUE)
            return new View(State.CAPTURE_PENDING, false, false, false, false);
        if (decision.disposition() == CaptureShipmentPolicy.Disposition.ALREADY_APPLIED)
            return new View(State.CAPTURED, false, false, false, false);
        // INCONSISTENT_STATE is reached only after identity, unique authorization, snapshot and amount checks.
        if (decision.reason() == CaptureShipmentPolicy.Reason.INCONSISTENT_STATE && authorizedCard
                && capture.getStatus() == PaymentTransactionStatus.FAILED && order.getPaidAt() == null
                && capture.getCompletedAt() != null && !capture.getCompletedAt().isBefore(order.getShippedAt())
                && Objects.equals(capture.getProviderTransactionId(), card.getProviderPaymentId()))
            return new View(State.CAPTURE_FAILED, false, false, false, false);
        return unavailable();
    }

    private PaymentOperationSnapshot snapshot(PaymentTransaction t) {
        Payment p = t.getPayment();
        return new PaymentOperationSnapshot(p.getOrder().getId(), p.getId(), t.getId(), t.getTransactionType(),
                t.getIdempotencyKey(), t.getAmount(), t.getOrderContentRevision(), p.getProviderPaymentId(),
                p.getProvider(), t.getStatus(), t.getInitiatorType(), t.getInitiatorId(), t.getInitiatorUsername(), t.getInternalNote(), t.getPurpose());
    }

    private boolean validData(Order order, List<Payment> payments, List<PaymentTransaction> history) {
        if (order == null || order.getId() == null || order.getId() <= 0 || order.getStatus() == null || payments == null || history == null) return false;
        Set<Long> ids = new HashSet<>(), transactionIds = new HashSet<>();
        for (Payment p : payments) if (p == null || p.getId() == null || p.getId() <= 0 || p.getAmount() <= 0 || p.getProvider() == null || p.getPaymentMethod() == null || !ids.add(p.getId()) || p.getStatus() == null
                || p.getOrder() == null || !Objects.equals(p.getOrder().getId(), order.getId())) return false;
        for (PaymentTransaction t : history) if (t == null || t.getId() == null || t.getId() <= 0 || t.getAmount() <= 0 || t.getOrderContentRevision() < 0 || t.getCreatedAt() == null
                || t.getIdempotencyKey() == null || t.getIdempotencyKey().isBlank() || !transactionIds.add(t.getId())
                || t.getStatus() == null || t.getTransactionType() == null || t.getPayment() == null
                || !ids.contains(t.getPayment().getId()) || t.getPayment().getOrder() == null
                || !Objects.equals(t.getPayment().getOrder().getId(), order.getId())) return false;
        return true;
    }
}
