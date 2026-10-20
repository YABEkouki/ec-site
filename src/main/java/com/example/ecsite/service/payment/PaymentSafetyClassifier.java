package com.example.ecsite.service.payment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        var purposes = new ArrayList<TransactionPurpose>();
        if (!validStructure(snapshot)) {
            issue(issues, Reason.INVALID_INPUT, null, null, "Invalid identity, ownership, timestamp, status or duplicate row/key");
            return result(null, purposes, issues);
        }
        if (!snapshot.complete()) issue(issues, Reason.INCOMPLETE_HISTORY, null, null, "Complete persisted row sets not attested");
        if (!validOrderHistory(snapshot)) issue(issues, Reason.ORDER_HISTORY_MISMATCH, null, null, "Lifecycle chain disagrees with order/actor/timestamps");

        // Stable ordering also makes diagnostics independent of database row ordering.
        var payments = snapshot.payments().stream().sorted(Comparator.comparing(PaymentFacts::id)).toList();
        var transactions = snapshot.transactions().stream().sorted(Comparator.comparing(TransactionFacts::id)).toList();
        for (var transaction : transactions) {
            var inferred = inferPurpose(snapshot, transaction);
            var purpose = inferred;
            if (transaction.declaredPurpose() != null) {
                try { purpose = PaymentTransactionPurpose.valueOf(transaction.declaredPurpose()); }
                catch (IllegalArgumentException ex) { purpose = LEGACY_UNKNOWN; }
            }
            purposes.add(new TransactionPurpose(transaction.id(), purpose,
                    transaction.declaredPurpose() == null ? "Persisted type and lifecycle context: " + transaction.type() : "Declared purpose: " + transaction.declaredPurpose()));
            if (!purposePolicy.isUsableInStage1A(transaction.type(), purpose) || purpose != inferred) {
                issue(issues, inferred == LEGACY_UNKNOWN ? Reason.UNKNOWN_TRANSACTION : Reason.UNSUPPORTED_PURPOSE,
                        transaction.paymentId(), transaction.id(), "Cannot establish supported business intent from type, declared purpose and lifecycle");
            }
        }

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
        return result(selected, purposes, issues);
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
                    || !afterAuthorization(authorization, cancels.getFirst())
                    || s.order().cancelledAt() == null || cancels.getFirst().completedAt() == null
                    || cancels.getFirst().completedAt().isAfter(s.order().cancelledAt())
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
                || !Objects.equals(capture.initiatorUsername(), order.shippedByUsername())
                || capture.createdAt().isBefore(order.shippedAt())
                || !afterAuthorization(auths.getFirst(), capture)
                || auths.getFirst().completedAt().isAfter(order.shippedAt())) {
            issue(issues, Reason.SHIPMENT_MISMATCH, p.id(), capture.id(), "Capture amount/revision/actor/time disagrees with fixed shipment");
        }
        boolean pending = capture.status().equals("PENDING") && p.status().equals("AUTHORIZED") && order.paidAt() == null;
        boolean succeeded = capture.status().equals("SUCCEEDED") && p.status().equals("CAPTURED")
                && order.paidAt() != null && !order.paidAt().isBefore(order.shippedAt())
                && capture.completedAt() != null && !capture.completedAt().isBefore(order.shippedAt())
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

    private boolean afterAuthorization(TransactionFacts authorization, TransactionFacts dependent) {
        return authorization.status().equals("SUCCEEDED") && authorization.completedAt() != null
                && !dependent.createdAt().isBefore(authorization.completedAt());
    }

    private boolean matches(CaptureEvidence e, PaymentFacts p, TransactionFacts t) {
        return e.verification() == CaptureVerification.VERIFIED && Objects.equals(e.paymentId(), p.id())
                && Objects.equals(e.providerPaymentId(), p.providerPaymentId()) && e.amount() == t.amount()
                && e.contentRevision() == t.contentRevision() && Objects.equals(e.idempotencyKey(), t.idempotencyKey());
    }

    private boolean validOrderHistory(PaymentSafetySnapshot s) {
        var o = s.order();
        var history = s.statusHistory().stream().sorted(Comparator.comparing(StatusHistory::changedAt).thenComparing(StatusHistory::id)).toList();
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
        if (cancelled && o.cancelledAt().isAfter(last.changedAt())) return false;
        if (shipped) {
            if (!positive(o.shippedByAccountId()) || !nonblank(o.shippedByUsername()) || o.shippedByUsername().length() > 100
                    || !"ADMIN".equals(last.actorType()) || !Objects.equals(last.actorId(), o.shippedByAccountId())
                    || !Objects.equals(last.actorUsername(), o.shippedByUsername()) || o.shippedAt().isAfter(last.changedAt())) return false;
            if (o.shipmentCapturePaymentId() == null && (!"PAID".equals(last.fromStatus()) || o.paidAt() == null
                    || o.paidAt().isAfter(o.shippedAt()))) return false;
        }
        if (o.status().equals("PAID") && (o.paidAt() == null || o.paidAt().isAfter(last.changedAt()))) return false;
        return true;
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
                    || t.createdAt() == null || (t.status().equals("PENDING") != (t.completedAt() == null))
                    || (t.completedAt() != null && t.completedAt().isBefore(t.createdAt()))) return false;
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
    private PaymentSafetyClassification result(Long selected, List<TransactionPurpose> purposes, List<ReviewIssue> issues) {
        return new PaymentSafetyClassification(issues.isEmpty()
                ? (selected == null ? ActivePaymentDisposition.NORMAL_NULL : ActivePaymentDisposition.SELECTED)
                : ActivePaymentDisposition.REVIEW, issues.isEmpty() ? selected : null, purposes, issues);
    }
}
