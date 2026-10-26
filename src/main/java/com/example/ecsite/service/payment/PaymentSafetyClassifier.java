package com.example.ecsite.service.payment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.ZoneId;
import static com.example.ecsite.service.payment.PaymentSafetySnapshot.TimeField.*;

import com.example.ecsite.entity.PaymentTransactionPurpose;
import static com.example.ecsite.entity.PaymentTransactionPurpose.*;
import static com.example.ecsite.service.payment.PaymentSafetyClassification.*;
import static com.example.ecsite.service.payment.PaymentSafetySnapshot.*;

/**
 * Pure, conservative classification of persisted references, NOT a provider-state observation
 * or execution permit. No latest-payment heuristic; every supplied row participates.
 * Missing/ambiguous evidence requires review rather than an invented legacy business intent.
 */
public final class PaymentSafetyClassifier {
    private static final Set<String> ORDER_STATES = Set.of("ORDERED", "PAID", "SHIPPED", "CANCELLED");
    private static final Set<String> PAYMENT_STATES = Set.of("PENDING", "REQUIRES_ACTION", "AUTHORIZED", "CAPTURED", "CANCELLED", "FAILED");
    private static final Set<String> TRANSACTION_STATES = Set.of("PENDING", "SUCCEEDED", "FAILED");
    private final PaymentPurposePolicy purposePolicy = new PaymentPurposePolicy();

    public PaymentSafetyClassification classify(PaymentSafetySnapshot snapshot) {
        var issues = new ArrayList<ReviewIssue>();
        var comparisons = new ArrayList<TimeComparison>();
        if (!validStructure(snapshot)) {
            issue(issues, Reason.INVALID_INPUT, null, null, "Invalid identity, ownership, timestamp, status or duplicate row/key");
            return result(snapshot, null, classifyPurposes(snapshot, false), issues, comparisons);
        }
        if (!snapshot.complete()) issue(issues, Reason.INCOMPLETE_HISTORY, null, null, "Complete persisted row sets not attested");
        boolean validHistory = validOrderHistory(snapshot);
        if (!validHistory) issue(issues, Reason.ORDER_HISTORY_MISMATCH, null, null, "Lifecycle chain disagrees with order/actor/metadata");
        var purposes = classifyPurposes(snapshot, snapshot.complete() && validHistory);

        // Payment diagnostics use ID order; purpose results retain their explicit input-row indexes.
        var payments = snapshot.payments().stream().sorted(Comparator.comparing(PaymentFacts::id)).toList();
        var transactions = snapshot.transactions().stream().sorted(Comparator.comparing(TransactionFacts::id)).toList();
        for (var purpose : purposes) {
            if (purpose.disposition() != PurposeDisposition.CONFIRMED) {
                var transaction = snapshot.transactions().get(purpose.inputIndex());
                issue(issues, purpose.disposition() == PurposeDisposition.CONFLICT ? Reason.UNSUPPORTED_PURPOSE : Reason.UNKNOWN_TRANSACTION,
                        transaction.paymentId(), transaction.id(), purpose.evidence());
            }
        }
        validateTimes(snapshot, comparisons, issues);

        for (var payment : payments) validatePayment(snapshot, payment, issues);
        var active = payments.stream().filter(p -> !Set.of("FAILED", "CANCELLED").contains(p.status())).toList();
        if (active.size() > 1) issue(issues, Reason.AMBIGUOUS_PAYMENT, null, null, "Multiple non-terminal payments; no latest selection");
        Long selected = null;
        var order = snapshot.order();
        if (order.status().equals("SHIPPED") && order.shipmentCapturePaymentId() != null) {
            if (active.size() != 1 || !Objects.equals(active.getFirst().id(), order.shipmentCapturePaymentId())) {
                issue(issues, Reason.SHIPMENT_MISMATCH, order.shipmentCapturePaymentId(), null, "Fixed shipment Payment is not the sole active Payment");
            } else {
                selected = active.getFirst().id();
                validateShipment(snapshot, active.getFirst(), issues);
            }
        } else if (order.status().equals("ORDERED")) {
            if (active.size() == 1) {
                var payment = active.getFirst();
                selected = payment.id();
                validateOrdered(snapshot, payment, issues);
            }
            if (transactions.stream().anyMatch(t -> !t.type().equals("AUTHORIZE"))) {
                issue(issues, Reason.INCONSISTENT_STATE, null, null, "Non-authorization history on an unshipped active order");
            }
        } else if (!active.isEmpty()) {
            issue(issues, Reason.INCONSISTENT_STATE, null, null, "Live financial state on terminal/manual order");
        }
        return result(snapshot, selected, purposes, issues, comparisons);
    }

    private List<TransactionPurpose> classifyPurposes(PaymentSafetySnapshot s, boolean contextEstablished) {
        if (s == null) return List.of();
        var results = new ArrayList<TransactionPurpose>();
        for (int index = 0; index < s.transactions().size(); index++) {
            var t = s.transactions().get(index);
            results.add(classifyPurpose(s, t, index, contextEstablished));
        }
        return results;
    }

    private TransactionPurpose classifyPurpose(PaymentSafetySnapshot s, TransactionFacts t, int index, boolean established) {
        PaymentTransactionPurpose declared = null;
        if (t.declaredPurpose() != null) {
            try { declared = PaymentTransactionPurpose.valueOf(t.declaredPurpose()); }
            catch (IllegalArgumentException failure) {
                return unresolved(t, index, PurposeDisposition.UNKNOWN, "Unrecognized declared purpose");
            }
            if (declared == LEGACY_UNKNOWN) return unresolved(t, index, PurposeDisposition.UNKNOWN, "Explicit LEGACY_UNKNOWN is never reinterpreted");
            if (!purposePolicy.isValidCombination(t.type(), declared)) {
                return unresolved(t, index, PurposeDisposition.CONFLICT, "Declared purpose contradicts transaction type");
            }
            if (!purposePolicy.isUsableInStage1A(t.type(), declared)) {
                return unresolved(t, index, PurposeDisposition.UNKNOWN, "Future business purpose is disabled");
            }
        }
        if (!established) return unresolved(t, index, PurposeDisposition.UNKNOWN, "Complete valid row sets and lifecycle evidence are required");
        var inferred = inferPurpose(s, t);
        if (inferred == LEGACY_UNKNOWN) return unresolved(t, index, PurposeDisposition.UNKNOWN, "Business intent is not established by lifecycle context");
        if (declared != null && declared != inferred) {
            return unresolved(t, index, PurposeDisposition.CONFLICT, "Declared purpose contradicts lifecycle context");
        }
        return new TransactionPurpose(index, t.id(), PurposeDisposition.CONFIRMED, inferred, t.declaredPurpose(),
                "Complete row sets, unique operation and lifecycle context: " + t.type());
    }

    private TransactionPurpose unresolved(TransactionFacts t, int index, PurposeDisposition disposition, String evidence) {
        return new TransactionPurpose(index, t.id(), disposition, LEGACY_UNKNOWN, t.declaredPurpose(), evidence);
    }

    private PaymentTransactionPurpose inferPurpose(PaymentSafetySnapshot s, TransactionFacts t) {
        var history = history(s, t.paymentId());
        if (t.type().equals("AUTHORIZE") && history.stream().filter(x -> x.type().equals("AUTHORIZE")).count() == 1) {
            return INITIAL_ORDER_AUTHORIZATION;
        }
        if (t.type().equals("CANCEL") && Set.of("ORDERED", "CANCELLED").contains(s.order().status())
                && validActor(t.initiatorType(), t.initiatorId(), t.initiatorUsername())
                && history.stream().noneMatch(x -> x.type().equals("CAPTURE"))
                && history.stream().filter(x -> x.type().equals("AUTHORIZE") && x.status().equals("SUCCEEDED")).count() == 1) {
            return ORDER_CANCELLATION;
        }
        if (t.type().equals("CAPTURE") && s.order().status().equals("SHIPPED")
                && Objects.equals(t.paymentId(), s.order().shipmentCapturePaymentId())
                && s.statusHistory().stream().anyMatch(h -> h.toStatus().equals("SHIPPED") && Objects.equals(h.fromStatus(), "ORDERED"))) {
            return SHIPMENT_CAPTURE;
        }
        return LEGACY_UNKNOWN;
    }

    private void validatePayment(PaymentSafetySnapshot s, PaymentFacts p, List<ReviewIssue> issues) {
        if (!contains(Set.of("PAYJP", "MOCK"), p.provider()) || !"CARD".equals(p.paymentMethod()) || !nonblank(p.providerPaymentId())) {
            issue(issues, Reason.INVALID_PAYMENT, p.id(), null, "Unknown provider/method or missing provider Payment ID");
        }
        var rows = history(s, p.id());
        var authorizations = rows.stream().filter(t -> t.type().equals("AUTHORIZE")).toList();
        if (authorizations.size() != 1) {
            issue(issues, Reason.AUTHORIZATION_MISMATCH, p.id(), null, "Exactly one initial authorization required per legacy Payment");
            return;
        }
        var authorization = authorizations.getFirst();
        if (authorization.amount() != p.amount()
                || (authorization.status().equals("SUCCEEDED") && !Objects.equals(authorization.providerTransactionId(), p.providerPaymentId()))) {
            issue(issues, Reason.AUTHORIZATION_MISMATCH, p.id(), authorization.id(), "Payment/auth amount or provider identity mismatch");
        }
        for (var row : rows) {
            if (row.providerTransactionId() != null && !Objects.equals(row.providerTransactionId(), p.providerPaymentId())) {
                issue(issues, Reason.INVALID_PAYMENT, p.id(), row.id(), "Transaction refers to a different provider Payment");
            }
        }
        if (p.status().equals("FAILED")) {
            if (rows.size() != 1 || !authorization.status().equals("FAILED")) {
                issue(issues, Reason.INCONSISTENT_STATE, p.id(), null, "Failed Payment still has unresolved/successful financial history");
            }
        } else if (p.status().equals("CANCELLED")) {
            var cancels = rows.stream().filter(t -> t.type().equals("CANCEL")).toList();
            if (!s.order().status().equals("CANCELLED") || !authorization.status().equals("SUCCEEDED")
                    || cancels.size() != 1 || !cancels.getFirst().status().equals("SUCCEEDED")
                    || cancels.getFirst().amount() != p.amount() || rows.size() != 2
                    || cancels.getFirst().contentRevision() != s.order().contentRevision()
                    || s.order().cancelledAt() == null || cancels.getFirst().completedAt() == null
                    || !Objects.equals(cancels.getFirst().providerTransactionId(), p.providerPaymentId())) {
                issue(issues, Reason.INCONSISTENT_STATE, p.id(), null, "Cancellation is not fully established by persisted evidence");
            }
            if (cancels.size() == 1 && s.statusHistory().stream().filter(h -> h.toStatus().equals("CANCELLED"))
                    .noneMatch(h -> Objects.equals(h.actorType(), cancels.getFirst().initiatorType())
                            && Objects.equals(h.actorId(), cancels.getFirst().initiatorId())
                            && Objects.equals(h.actorUsername(), cancels.getFirst().initiatorUsername()))) {
                issue(issues, Reason.ORDER_HISTORY_MISMATCH, p.id(), cancels.getFirst().id(), "Cancellation actor disagrees with lifecycle history");
            }
        } else if (Set.of("AUTHORIZED", "CAPTURED").contains(p.status()) && !authorization.status().equals("SUCCEEDED")) {
            issue(issues, Reason.AUTHORIZATION_MISMATCH, p.id(), authorization.id(), "Payment state requires successful authorization");
        } else if (Set.of("PENDING", "REQUIRES_ACTION").contains(p.status()) && !authorization.status().equals("PENDING")) {
            issue(issues, Reason.INCONSISTENT_STATE, p.id(), authorization.id(), "Pending Payment conflicts with completed authorization");
        }
    }

    private void validateOrdered(PaymentSafetySnapshot s, PaymentFacts p, List<ReviewIssue> issues) {
        var rows = history(s, p.id());
        if (rows.size() != 1 || !rows.getFirst().type().equals("AUTHORIZE")) {
            issue(issues, Reason.PENDING_CONFLICT, p.id(), null, "Initial authorization must be the sole operation on active ordered Payment");
            return;
        }
        var auth = rows.getFirst();
        if (p.status().equals("AUTHORIZED")) {
            if (!authorizationConsistent(s.order(), p, auth)) issue(issues, Reason.AUTHORIZATION_MISMATCH, p.id(), auth.id(), "Amount/revision not capture-consistent");
        } else if (Set.of("PENDING", "REQUIRES_ACTION").contains(p.status())) {
            if (!auth.status().equals("PENDING") || s.order().totalAmount() != p.amount()
                    || s.order().contentRevision() != auth.contentRevision()) {
                issue(issues, Reason.AUTHORIZATION_MISMATCH, p.id(), auth.id(), "Initial pending request must match exact order amount/revision");
            }
        } else issue(issues, Reason.INCONSISTENT_STATE, p.id(), null, "Ordered Payment is not pending or authorized");
    }

    private void validateShipment(PaymentSafetySnapshot s, PaymentFacts p, List<ReviewIssue> issues) {
        var order = s.order();
        var rows = history(s, p.id());
        var captures = rows.stream().filter(t -> t.type().equals("CAPTURE")).toList();
        var auths = rows.stream().filter(t -> t.type().equals("AUTHORIZE") && t.status().equals("SUCCEEDED")).toList();
        if (!"PAYJP".equals(p.provider()) || !order.shippingPreparationComplete() || order.shippedAt() == null || captures.size() != 1
                || rows.size() != 2 || auths.size() != 1 || !authorizationConsistent(order, p, auths.getFirst())) {
            issue(issues, Reason.SHIPMENT_MISMATCH, p.id(), null, "Shipment authorization/preparation/history mismatch");
            return;
        }
        var capture = captures.getFirst();
        if (capture.amount() != order.totalAmount() || capture.contentRevision() != order.contentRevision()
                || !"ADMIN".equals(capture.initiatorType()) || !Objects.equals(capture.initiatorId(), order.shippedByAccountId())
                || !Objects.equals(capture.initiatorUsername(), order.shippedByUsername())) {
            issue(issues, Reason.SHIPMENT_MISMATCH, p.id(), capture.id(), "Capture amount/revision/actor/time disagrees with fixed shipment");
        }
        boolean pending = capture.status().equals("PENDING") && p.status().equals("AUTHORIZED") && order.paidAt() == null;
        boolean succeeded = capture.status().equals("SUCCEEDED") && p.status().equals("CAPTURED")
                && order.paidAt() != null && capture.completedAt() != null
                && Objects.equals(capture.providerTransactionId(), p.providerPaymentId());
        if (!pending && !succeeded) issue(issues, Reason.INCONSISTENT_STATE, p.id(), capture.id(), "Capture status disagrees with shipped/paid Payment state");
        var evidence = s.captureEvidence().stream().filter(e -> Objects.equals(e.transactionId(), capture.id())).toList();
        if (evidence.size() != 1 || !matches(evidence.getFirst(), p, capture)) {
            issue(issues, Reason.CAPTURE_REQUEST_UNVERIFIED, p.id(), capture.id(), "V54 immutable request missing, unverified or mismatched");
        }
        if (s.transactions().stream().anyMatch(t -> t.status().equals("PENDING") && !t.id().equals(capture.id()))) {
            issue(issues, Reason.PENDING_CONFLICT, p.id(), null, "Another unresolved transaction exists during shipment");
        }
    }

    /** Same amount/revision semantics as PaymentConsistencyEvaluator.canCapture(). */
    private boolean authorizationConsistent(OrderFacts order, PaymentFacts p, TransactionFacts auth) {
        return p.amount() == auth.amount() && (order.totalAmount() < p.amount()
                || (order.totalAmount() == p.amount() && order.contentRevision() == auth.contentRevision()));
    }

    private boolean matches(CaptureEvidence e, PaymentFacts p, TransactionFacts t) {
        return e.verification() == CaptureVerification.VERIFIED && Objects.equals(e.paymentId(), p.id())
                && Objects.equals(e.providerPaymentId(), p.providerPaymentId()) && e.amount() == t.amount()
                && e.contentRevision() == t.contentRevision() && Objects.equals(e.idempotencyKey(), t.idempotencyKey());
    }

    private boolean validOrderHistory(PaymentSafetySnapshot s) {
        var o = s.order();
        var history = lifecycleHistory(s);
        if (history.isEmpty()) return false;
        String state = null;
        for (var h : history) {
            if (!Objects.equals(h.fromStatus(), state) || !allowedTransition(state, h.toStatus())) return false;
            state = h.toStatus();
        }
        if (!o.status().equals(state)) return false;
        boolean shipped = o.status().equals("SHIPPED"), cancelled = o.status().equals("CANCELLED");
        if (cancelled != (o.cancelledAt() != null) || shipped != (o.shippedAt() != null)) return false;
        if (!shipped && (o.shipmentCapturePaymentId() != null || o.shippedByAccountId() != null || o.shippedByUsername() != null)) return false;
        if (Set.of("ORDERED", "CANCELLED").contains(o.status()) && o.paidAt() != null) return false;
        var last = history.getLast();
        if (shipped) {
            if (!positive(o.shippedByAccountId()) || !nonblank(o.shippedByUsername()) || o.shippedByUsername().length() > 100
                    || !"ADMIN".equals(last.actorType()) || !Objects.equals(last.actorId(), o.shippedByAccountId())
                    || !Objects.equals(last.actorUsername(), o.shippedByUsername()) || !o.shippingPreparationComplete()) return false;
            if (o.shipmentCapturePaymentId() == null && (!"PAID".equals(last.fromStatus()) || o.paidAt() == null)) return false;
        }
        if (o.status().equals("PAID") && o.paidAt() == null) return false;
        if (o.shippingPreparation() == null || (!o.shippingPreparation().absent() && !o.shippingPreparationComplete())) return false;
        return true;
    }

    /** Reconstruct the unique state chain; transaction-start timestamps are not commit order. */
    private List<StatusHistory> lifecycleHistory(PaymentSafetySnapshot s) {
        var remaining = new ArrayList<>(s.statusHistory());
        var ordered = new ArrayList<StatusHistory>();
        String state = null;
        while (!remaining.isEmpty()) {
            var next = new ArrayList<StatusHistory>();
            for (var h : remaining) if (Objects.equals(h.fromStatus(), state)) next.add(h);
            if (next.size() != 1 || !allowedTransition(state, next.getFirst().toStatus())) return List.of();
            var event = next.getFirst();
            ordered.add(event);
            remaining.remove(event);
            state = event.toStatus();
        }
        return ordered;
    }

    private void validateTimes(PaymentSafetySnapshot s, List<TimeComparison> comparisons, List<ReviewIssue> issues) {
        var orderId = s.order().id();
        for (var t : s.transactions()) {
            compare(s, time(TRANSACTION_CREATED, t.id()), time(TRANSACTION_COMPLETED, t.id()), comparisons, issues);
            if (t.type().equals("CAPTURE") && Objects.equals(t.paymentId(), s.order().shipmentCapturePaymentId())) {
                compare(s, time(ORDER_SHIPPED, orderId), time(TRANSACTION_CREATED, t.id()), comparisons, issues);
                compare(s, time(ORDER_SHIPPED, orderId), time(TRANSACTION_COMPLETED, t.id()), comparisons, issues);
            }
            if (t.type().equals("CANCEL") && t.status().equals("SUCCEEDED") && s.order().status().equals("CANCELLED")) {
                compare(s, time(TRANSACTION_COMPLETED, t.id()), time(ORDER_CANCELLED, orderId), comparisons, issues);
            }
        }
        for (var p : s.payments()) {
            var rows = history(s, p.id());
            var auths = rows.stream().filter(t -> t.type().equals("AUTHORIZE") && t.status().equals("SUCCEEDED")).toList();
            if (auths.size() != 1) continue;
            var auth = auths.getFirst();
            for (var t : rows) if (Set.of("CANCEL", "CAPTURE").contains(t.type())) {
                compare(s, time(TRANSACTION_COMPLETED, auth.id()), time(TRANSACTION_CREATED, t.id()), comparisons, issues);
            }
            if (Objects.equals(p.id(), s.order().shipmentCapturePaymentId())) {
                compare(s, time(TRANSACTION_COMPLETED, auth.id()), time(ORDER_SHIPPED, orderId), comparisons, issues);
            }
        }
        compare(s, time(PREPARATION_STARTED, orderId), time(ORDER_SHIPPED, orderId), comparisons, issues);
        if (s.order().shipmentCapturePaymentId() == null) {
            compare(s, time(ORDER_PAID, orderId), time(ORDER_SHIPPED, orderId), comparisons, issues);
        } else {
            compare(s, time(ORDER_SHIPPED, orderId), time(ORDER_PAID, orderId), comparisons, issues);
        }
        var lifecycle = lifecycleHistory(s);
        for (int i = 0; i < lifecycle.size(); i++) {
            var h = lifecycle.get(i);
            if (i > 0) compare(s, time(HISTORY_CHANGED, lifecycle.get(i - 1).id()), time(HISTORY_CHANGED, h.id()), comparisons, issues);
            var businessField = switch (h.toStatus()) {
                case "SHIPPED" -> ORDER_SHIPPED;
                case "CANCELLED" -> ORDER_CANCELLED;
                case "PAID" -> ORDER_PAID;
                default -> null;
            };
            if (businessField != null) compare(s, time(businessField, orderId), time(HISTORY_CHANGED, h.id()), comparisons, issues);
        }
    }

    private void compare(PaymentSafetySnapshot s, TimeReference earlier, TimeReference later,
            List<TimeComparison> comparisons, List<ReviewIssue> issues) {
        var firstValue = timestamp(s, earlier);
        var secondValue = timestamp(s, later);
        // Nullable, absent events do not assert an ordering; required fields are validated separately.
        if (firstValue == null || secondValue == null) return;
        var firstOrigin = s.timeOrigins().getOrDefault(earlier, TimeOrigin.unknown());
        var secondOrigin = s.timeOrigins().getOrDefault(later, TimeOrigin.unknown());
        var reason = comparisonReason(firstOrigin, secondOrigin, firstValue, secondValue);
        var outcome = reason != TimeComparisonReason.SAME_WALL_CLOCK_AND_ZONE ? TimeComparisonOutcome.NOT_COMPARABLE
                : (firstValue.atZone(ZoneId.of(firstOrigin.zoneId())).toInstant()
                        .isAfter(secondValue.atZone(ZoneId.of(secondOrigin.zoneId())).toInstant())
                        ? TimeComparisonOutcome.INVERTED : TimeComparisonOutcome.ORDERED);
        comparisons.add(new TimeComparison(earlier, firstValue, firstOrigin, later, secondValue, secondOrigin, outcome, reason));
        if (outcome == TimeComparisonOutcome.INVERTED) {
            var transactionId = isTransactionTime(earlier) ? earlier.rowId() : (isTransactionTime(later) ? later.rowId() : null);
            issue(issues, Reason.TEMPORAL_CONTRADICTION, null, transactionId, "Comparable readings are reversed: " + earlier + " > " + later);
        }
    }

    private TimeComparisonReason comparisonReason(TimeOrigin first, TimeOrigin second,
            LocalDateTime firstValue, LocalDateTime secondValue) {
        if (first.kind() == ClockKind.DATABASE_TRANSACTION_START || second.kind() == ClockKind.DATABASE_TRANSACTION_START) {
            return TimeComparisonReason.TRANSACTION_START_IS_NOT_EVENT_TIME;
        }
        if (first.kind() == ClockKind.UNKNOWN || second.kind() == ClockKind.UNKNOWN) return TimeComparisonReason.UNKNOWN_CLOCK;
        if (first.kind() == null || second.kind() == null || !nonblank(first.clockDomain()) || !nonblank(second.clockDomain())
                || !nonblank(first.zoneId()) || !nonblank(second.zoneId())) return TimeComparisonReason.INVALID_CLOCK_METADATA;
        try {
            var firstZone = ZoneId.of(first.zoneId());
            var secondZone = ZoneId.of(second.zoneId());
            if (first.kind() != second.kind() || !first.clockDomain().equals(second.clockDomain()) || !firstZone.equals(secondZone)) {
                return TimeComparisonReason.DIFFERENT_CLOCK_OR_ZONE;
            }
            if (firstZone.getRules().getValidOffsets(firstValue).size() != 1
                    || secondZone.getRules().getValidOffsets(secondValue).size() != 1) {
                return TimeComparisonReason.LOCAL_TIME_NOT_UNIQUE;
            }
            return TimeComparisonReason.SAME_WALL_CLOCK_AND_ZONE;
        } catch (java.time.DateTimeException invalidZone) {
            return TimeComparisonReason.INVALID_CLOCK_METADATA;
        }
    }

    private LocalDateTime timestamp(PaymentSafetySnapshot s, TimeReference reference) {
        return switch (reference.field()) {
            case ORDER_PAID -> s.order().paidAt();
            case ORDER_CANCELLED -> s.order().cancelledAt();
            case ORDER_SHIPPED -> s.order().shippedAt();
            case PREPARATION_STARTED -> s.order().shippingPreparation() == null ? null : s.order().shippingPreparation().startedAt();
            case TRANSACTION_CREATED, TRANSACTION_COMPLETED -> s.transactions().stream()
                    .filter(t -> Objects.equals(t.id(), reference.rowId()))
                    .map(t -> reference.field() == TRANSACTION_CREATED ? t.createdAt() : t.completedAt())
                    .filter(Objects::nonNull).findFirst().orElse(null);
            case HISTORY_CHANGED -> s.statusHistory().stream().filter(h -> Objects.equals(h.id(), reference.rowId()))
                    .map(StatusHistory::changedAt).findFirst().orElse(null);
        };
    }
    private TimeReference time(TimeField field, Long rowId) { return new TimeReference(field, rowId); }
    private boolean isTransactionTime(TimeReference reference) {
        return reference.field() == TRANSACTION_CREATED || reference.field() == TRANSACTION_COMPLETED;
    }

    private boolean allowedTransition(String from, String to) {
        if (from == null) return to.equals("ORDERED");
        return switch (from) {
            case "ORDERED" -> Set.of("PAID", "CANCELLED", "SHIPPED").contains(to);
            case "PAID" -> to.equals("SHIPPED");
            default -> false;
        };
    }

    private boolean validStructure(PaymentSafetySnapshot s) {
        if (s == null || s.order() == null) return false;
        var o = s.order();
        if (!positive(o.id()) || !contains(ORDER_STATES, o.status()) || o.totalAmount() <= 0 || o.contentRevision() < 0) return false;
        Set<Long> paymentIds = new HashSet<>(), transactionIds = new HashSet<>(), historyIds = new HashSet<>(), evidenceIds = new HashSet<>();
        Set<String> keys = new HashSet<>(), providerIds = new HashSet<>();
        for (var p : s.payments()) {
            if (!positive(p.id()) || !paymentIds.add(p.id()) || !Objects.equals(p.orderId(), o.id())
                    || !contains(PAYMENT_STATES, p.status()) || p.amount() <= 0) return false;
            if (nonblank(p.providerPaymentId()) && !providerIds.add(p.provider() + ":" + p.providerPaymentId())) return false;
        }
        for (var t : s.transactions()) {
            if (!positive(t.id()) || !transactionIds.add(t.id()) || !paymentIds.contains(t.paymentId())
                    || t.type() == null || !contains(TRANSACTION_STATES, t.status()) || t.amount() <= 0 || t.contentRevision() < 0
                    || !nonblank(t.idempotencyKey()) || t.idempotencyKey().length() > 100 || !keys.add(t.idempotencyKey())
                    || t.createdAt() == null || (t.status().equals("PENDING") != (t.completedAt() == null))) return false;
        }
        for (var h : s.statusHistory()) {
            if (!positive(h.id()) || !historyIds.add(h.id()) || !Objects.equals(h.orderId(), o.id()) || h.changedAt() == null
                    || !contains(ORDER_STATES, h.toStatus()) || !validActor(h.actorType(), h.actorId(), h.actorUsername())) return false;
        }
        for (var e : s.captureEvidence()) {
            if (!transactionIds.contains(e.transactionId()) || !evidenceIds.add(e.transactionId())
                    || s.transactions().stream().noneMatch(t -> t.id().equals(e.transactionId()) && t.type().equals("CAPTURE"))) return false;
        }
        return true;
    }

    private List<TransactionFacts> history(PaymentSafetySnapshot s, Long paymentId) {
        return s.transactions().stream().filter(t -> Objects.equals(t.paymentId(), paymentId)).toList();
    }
    private boolean validActor(String type, Long id, String username) {
        return contains(Set.of("USER", "ADMIN", "SYSTEM"), type) && nonblank(username)
                && username.length() <= 100 && ("SYSTEM".equals(type) || positive(id));
    }
    private boolean contains(Set<String> values, String value) { return value != null && values.contains(value); }
    private boolean positive(Long id) { return id != null && id > 0; }
    private boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private void issue(List<ReviewIssue> issues, Reason reason, Long paymentId, Long transactionId, String evidence) {
        issues.add(new ReviewIssue(reason, paymentId, transactionId, evidence));
    }
    private PaymentSafetyClassification result(PaymentSafetySnapshot snapshot, Long selected, List<TransactionPurpose> purposes,
            List<ReviewIssue> issues, List<TimeComparison> comparisons) {
        return new PaymentSafetyClassification(issues.isEmpty()
                ? (selected == null ? ActivePaymentDisposition.NORMAL_NULL : ActivePaymentDisposition.SELECTED)
                : ActivePaymentDisposition.REVIEW, issues.isEmpty() ? selected : null, purposes, issues,
                new ClassificationEvidence(snapshot == null || snapshot.order() == null ? null : snapshot.order().shippingPreparation(), comparisons));
    }
}
