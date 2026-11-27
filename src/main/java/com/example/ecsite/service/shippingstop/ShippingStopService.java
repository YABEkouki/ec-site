package com.example.ecsite.service.shippingstop;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.PaymentTransactionInitiator;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.payment.CancellationRequest;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import com.example.ecsite.service.payment.PaymentAuthorizationExpiryStore;
import com.example.ecsite.service.payment.PaymentCancellationCompletionService;
import com.example.ecsite.service.payment.PaymentCancellationStart;
import com.example.ecsite.service.payment.PaymentOperationSnapshot;
import com.example.ecsite.service.payment.PaymentOperationSyncService;
import com.example.ecsite.service.payment.PaymentPostGuardService;
import com.example.ecsite.service.payment.PaymentResultApplication;
import com.example.ecsite.service.payment.PaymentService;

/**
 * Feature146: an administrator allowed to ship urgently stops the shipment of a prepared, unshipped order
 * (docs/feature146/shipping-stop.md).
 * <ul>
 * <li>146-A, expired authorization: like Feature135 no cancel POST is sent. The Payment Flow is read outside any
 * transaction and must already be canceled; one transaction then records the COMPLETED stop (V114), the expired
 * cancellation (V94), the payment and order cancellation and the stock.</li>
 * <li>146-B, valid authorization: one transaction records the REQUESTED stop with the pending cancellation (V115);
 * the cancel POST is sent outside it. The canceled result completes the stop wherever it is applied (here, the
 * recheck, webhooks or reconciliation, see {@link ShippingStopRecords}). When PAY.JP still shows the authorization
 * capturable, the administrator may withdraw the stop: the cancellation fails and the order stays prepared.</li>
 * </ul>
 */
@Service
public class ShippingStopService {
    private static final Logger log = LoggerFactory.getLogger(ShippingStopService.class);

    public enum Outcome {
        /** The order is cancelled. */
        STOPPED,
        ALREADY_CANCELLED,
        /** The cancel POST was sent (or may have been); its result is not known yet. */
        REQUESTED,
        /** PAY.JP refused the cancel POST; the stop stays REQUESTED until it is withdrawn or rechecked. */
        REJECTED_BY_PAYJP,
        /** The recheck found the authorization not canceled yet. */
        STILL_REQUESTED,
        WITHDRAWN
    }

    /** What the order detail offers. */
    public enum State { NONE, AVAILABLE, NO_PERMISSION, BLOCKED, REQUESTED }

    public record Target(long orderId, long paymentId, String providerPaymentId, boolean expired) {}

    public record Requested(long orderId, long stopId, long transactionId, long paymentId, String providerPaymentId) {}

    public record Stop(LocalDateTime createdAt, String adminUsername, String reason, String authorizationKind,
            String preparationKind, String fileState, String status, LocalDateTime resolvedAt,
            String withdrawnByUsername, String withdrawalReason) {}

    public record View(State state, String message, String fileState, List<Stop> stops, boolean expired, boolean permitted) {}

    private final Transactions transactions;
    private final PaymentGateway gateway;
    private final PaymentPostGuardService postGuard;
    private final PaymentCancellationCompletionService completion;
    private final PaymentOperationSyncService synchronization;

    public ShippingStopService(Transactions transactions, PaymentGateway gateway, PaymentPostGuardService postGuard,
            PaymentCancellationCompletionService completion, PaymentOperationSyncService synchronization) {
        this.transactions = transactions;
        this.gateway = gateway;
        this.postGuard = postGuard;
        this.completion = completion;
        this.synchronization = synchronization;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Outcome stop(long orderId, long accountId, String username, String reason, boolean warehouseContacted) {
        String trimmed = requireReason(reason, "出荷中止の理由を500文字以内で入力してください。");
        if (!warehouseContacted) throw new ShippingStopException("倉庫に出荷しないよう連絡したことを確認してください。");
        requireAllowed(accountId);
        var target = transactions.target(orderId);
        if (target == null) return Outcome.ALREADY_CANCELLED;
        Outcome outcome = target.expired() ? stopExpired(target, accountId, username, trimmed) : stopValid(target, accountId, username, trimmed);
        log.info("Shipping stop: outcome={}, orderId={}, paymentId={}, expired={}", outcome, orderId, target.paymentId(), target.expired());
        return outcome;
    }

    private Outcome stopExpired(Target target, long accountId, String username, String reason) {
        var state = gateway.retrievePaymentFlow(target.providerPaymentId());
        if (state == null || state.status() != PaymentFlowStatus.CANCELED
                || !target.providerPaymentId().equals(state.providerPaymentId())) {
            throw new ShippingStopException(
                    "PAY.JP上でまだ与信が取り消されていません。期限切れから取消まで30分ほどかかることがあります。時間をおいて再度お試しください。");
        }
        return transactions.applyExpired(target, accountId, username, reason);
    }

    private Outcome stopValid(Target target, long accountId, String username, String reason) {
        PaymentCancellationStart start = transactions.startValid(target, accountId, username, reason);
        CancellationResult result;
        try {
            postGuard.beforeNewPost(start.snapshot());
            result = gateway.cancelAuthorization(new CancellationRequest(start.providerPaymentId(), start.idempotencyKey()));
        } catch (PaymentGatewayException e) {
            log.warn("Shipping stop cancel POST stopped: orderId={}, transactionId={}, category={}, status={}, resultUnknown={}",
                    target.orderId(), start.transactionId(), e.category(), e.httpStatus(), e.resultUnknown());
            return e.resultUnknown() ? Outcome.REQUESTED : Outcome.REJECTED_BY_PAYJP;
        } catch (RuntimeException e) {
            log.error("Shipping stop cancel POST failed: orderId={}, transactionId={}", target.orderId(), start.transactionId(), e);
            return Outcome.REQUESTED;
        }
        if (result == null || result.status() != CancellationResultStatus.CANCELLED) return Outcome.REQUESTED;
        try {
            completion.apply(target.orderId(), start, result);
        } catch (RuntimeException e) {
            // The stop stays REQUESTED; the recheck, webhooks and reconciliation apply the result again.
            log.error("Shipping stop result not applied: orderId={}, transactionId={}", target.orderId(), start.transactionId(), e);
            return Outcome.REQUESTED;
        }
        return Outcome.STOPPED;
    }

    /** Reads the Payment Flow of a REQUESTED stop and applies a canceled result like webhooks and reconciliation. */
    @Transactional(propagation = Propagation.NEVER)
    public Outcome recheck(long orderId, long accountId) {
        requireAllowed(accountId);
        var requested = transactions.requested(orderId);
        if (requested == null) return transactions.cancelled(orderId) ? Outcome.ALREADY_CANCELLED : Outcome.STILL_REQUESTED;
        synchronization.synchronize(transactions.snapshot(requested));
        return transactions.stopStatus(requested.stopId()).equals("COMPLETED") ? Outcome.STOPPED : Outcome.STILL_REQUESTED;
    }

    /**
     * Withdraws a REQUESTED stop after PAY.JP shows the authorization still capturable: the cancellation becomes
     * FAILED, the stop REJECTED, and the order stays prepared. A Flow already canceled completes the stop instead.
     */
    @Transactional(propagation = Propagation.NEVER)
    public Outcome withdraw(long orderId, long accountId, String username, String reason, boolean warehouseContacted) {
        String trimmed = requireReason(reason, "取りやめの理由を500文字以内で入力してください。");
        if (!warehouseContacted) throw new ShippingStopException("倉庫に出荷を続けるよう連絡したことを確認してください。");
        requireAllowed(accountId);
        var requested = transactions.requested(orderId);
        if (requested == null) throw new ShippingStopException("取りやめられる出荷中止はありません。");
        var state = gateway.retrievePaymentFlow(requested.providerPaymentId());
        if (state != null && state.status() == PaymentFlowStatus.CANCELED
                && requested.providerPaymentId().equals(state.providerPaymentId())) {
            synchronization.synchronize(transactions.snapshot(requested));
            if (transactions.stopStatus(requested.stopId()).equals("COMPLETED")) return Outcome.STOPPED;
            throw new ShippingStopException("PAY.JPでは与信が取り消されていますが、反映できませんでした。時間をおいて「PAY.JPで再確認」をお試しください。");
        }
        if (state == null || state.status() != PaymentFlowStatus.REQUIRES_CAPTURE
                || !requested.providerPaymentId().equals(state.providerPaymentId())) {
            throw new ShippingStopException("PAY.JPで与信がまだ有効であることを確認できないため、取りやめられません。");
        }
        transactions.withdraw(requested, accountId, username, trimmed);
        log.info("Shipping stop withdrawn: orderId={}, stopId={}, transactionId={}", orderId, requested.stopId(), requested.transactionId());
        return Outcome.WITHDRAWN;
    }

    @Transactional(propagation = Propagation.NEVER)
    public View view(long orderId, long accountId) {
        return transactions.view(orderId, accountId);
    }

    private static String requireReason(String reason, String message) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > 500) throw new ShippingStopException(message);
        return trimmed;
    }

    private void requireAllowed(long accountId) {
        if (!transactions.allowed(accountId)) throw new ShippingStopException(NO_PERMISSION);
    }

    @Service
    public static class Transactions {
        private final OrderRepository orders;
        private final PaymentService payments;
        private final OrderService orderService;
        private final OrderPaymentGuard guard;
        private final PaymentAuthorizationExpiryStore expiries;
        private final PaymentRepository paymentRepository;
        private final PaymentTransactionRepository paymentTransactions;
        private final com.example.ecsite.service.payment.PaymentOperationSnapshotService snapshots;
        private final JdbcTemplate jdbc;

        public Transactions(OrderRepository orders, PaymentService payments, OrderService orderService,
                OrderPaymentGuard guard, PaymentAuthorizationExpiryStore expiries, PaymentRepository paymentRepository,
                PaymentTransactionRepository paymentTransactions,
                com.example.ecsite.service.payment.PaymentOperationSnapshotService snapshots, JdbcTemplate jdbc) {
            this.snapshots = snapshots;
            this.orders = orders; this.payments = payments; this.orderService = orderService;
            this.guard = guard; this.expiries = expiries; this.paymentRepository = paymentRepository;
            this.paymentTransactions = paymentTransactions; this.jdbc = jdbc;
        }

        @Transactional(readOnly = true)
        public boolean allowed(long accountId) {
            List<Boolean> found = jdbc.queryForList(
                    "select enabled and emergency_shipping_allowed from admin_accounts where id=?", Boolean.class, accountId);
            return found.size() == 1 && Boolean.TRUE.equals(found.getFirst());
        }

        /** {@code null} when the order is already cancelled; otherwise throws unless it can be stopped now. */
        @Transactional(readOnly = true)
        public Target target(long orderId) {
            Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
            if (order.getStatus() == OrderStatus.CANCELLED) return null;
            requireStoppable(order);
            if (requested(orderId) != null) throw new ShippingStopException(REQUESTED_MESSAGE);
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from order_change_operations where order_id = ? and state not in ('COMPLETED','ABORTED'))
                        or f131_order_has_blocking_admission(?)
                    """, Boolean.class, orderId, orderId))) {
                throw new ShippingStopException("注文変更の処理が終わっていないため、出荷中止できません。");
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from warehouse_shipment_import_lines where order_id = ? and state in ('PENDING','CAPTURE_UNCONFIRMED'))
                        or exists(select 1 from payment_transactions t join payments p on p.id = t.payment_id
                                  where p.order_id = ? and t.transaction_type in ('CAPTURE','CANCEL') and t.status = 'PENDING')
                    """, Boolean.class, orderId, orderId))) {
                throw new ShippingStopException("出荷・売上確定・取消の処理中のため、出荷中止できません。");
            }
            List<Target> active = jdbc.query("""
                    select p.id, p.provider_payment_id, e.expires_at <= timezone('Asia/Tokyo', clock_timestamp()) as expired
                    from payments p join orders o on o.id = p.order_id
                    join payment_authorization_expiries e on e.payment_id = p.id
                    where o.id = ? and p.status = 'AUTHORIZED' and p.provider = 'PAYJP' and p.payment_method = 'CARD'
                      and (o.active_payment_id is null or o.active_payment_id = p.id)
                    """, (r, n) -> new Target(orderId, r.getLong(1), r.getString(2), r.getBoolean(3)), orderId);
            if (active.size() != 1 || active.getFirst().providerPaymentId() == null || active.getFirst().providerPaymentId().isBlank())
                throw new ShippingStopException("出荷中止の対象になる与信済みのカード決済がありません。");
            return active.getFirst();
        }

        @Transactional
        public Outcome applyExpired(Target expected, long accountId, String username, String reason) {
            Order order = lockStoppable(expected);
            if (order == null) return Outcome.ALREADY_CANCELLED;
            // Locks the Payments in ID order and re-validates them; refuses unless the recorded expiry has passed.
            var start = payments.startExpiredAuthorizationCancellation(order, initiator(accountId, username, reason));
            requireSamePayment(start, expected);
            insertStop(order, start, accountId, username, reason, "EXPIRED", "COMPLETED");
            expiries.recordExpiredCancellation(start.transactionId(), start.paymentId(), start.providerPaymentId());
            var applied = payments.applyCancellationResult(order, start.snapshot(),
                    new CancellationResult(CancellationResultStatus.CANCELLED, start.providerPaymentId()));
            if (applied != PaymentResultApplication.APPLIED) {
                throw new IllegalStateException("決済の取消を反映できませんでした: " + applied);
            }
            orderService.cancelOrderForShippingStop(order, accountId, username, "出荷中止：" + reason);
            return Outcome.STOPPED;
        }

        /** Records the REQUESTED stop and the pending cancellation; the caller sends the cancel POST afterwards. */
        @Transactional
        public PaymentCancellationStart startValid(Target expected, long accountId, String username, String reason) {
            Order order = lockStoppable(expected);
            if (order == null) throw new ShippingStopException("この注文は既に取り消されています。");
            // Locks the Payments in ID order and re-validates them; refuses once the recorded expiry has passed.
            var start = payments.startShippingStopCancellation(order, initiator(accountId, username, reason));
            requireSamePayment(start, expected);
            insertStop(order, start, accountId, username, reason, "VALID", "REQUESTED");
            return start;
        }

        @Transactional(readOnly = true)
        public Requested requested(long orderId) {
            List<Requested> found = jdbc.query("""
                    select s.id, s.transaction_id, s.payment_id, p.provider_payment_id from order_shipping_stops s
                    join payments p on p.id = s.payment_id where s.order_id = ? and s.status = 'REQUESTED'
                    """, (r, n) -> new Requested(orderId, r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4)), orderId);
            return found.isEmpty() ? null : found.getFirst();
        }

        @Transactional(readOnly = true)
        public boolean cancelled(long orderId) {
            return orders.findById(orderId).map(o -> o.getStatus() == OrderStatus.CANCELLED).orElse(false);
        }

        @Transactional(readOnly = true)
        public PaymentOperationSnapshot snapshot(Requested requested) {
            return snapshots.find(requested.transactionId())
                    .orElseThrow(() -> new IllegalStateException("取消の取引が見つかりません。"));
        }

        @Transactional(readOnly = true)
        public String stopStatus(long stopId) {
            return jdbc.queryForObject("select status from order_shipping_stops where id = ?", String.class, stopId);
        }

        @Transactional
        public void withdraw(Requested requested, long accountId, String username, String reason) {
            Order order = orders.findByIdForUpdate(requested.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(requested.orderId()));
            paymentRepository.findByOrderIdForUpdate(order.getId());
            var transaction = paymentTransactions.findById(requested.transactionId())
                    .orElseThrow(() -> new IllegalStateException("取消の取引が見つかりません。"));
            if (order.getStatus() != OrderStatus.ORDERED || transaction.getStatus() != PaymentTransactionStatus.PENDING
                    || transaction.getTransactionType() != PaymentTransactionType.CANCEL
                    || !Objects.equals(transaction.getPayment().getId(), requested.paymentId())) {
                throw new ShippingStopException("出荷中止の状態が変わったため、取りやめられません。画面を更新してください。");
            }
            int updated = jdbc.update("""
                    update order_shipping_stops set status = 'REJECTED', withdrawn_by_account_id = ?, withdrawn_by_username = ?,
                        withdrawal_reason = ? where id = ? and status = 'REQUESTED'
                    """, accountId, username, reason, requested.stopId());
            if (updated != 1) throw new ShippingStopException("出荷中止の状態が変わったため、取りやめられません。画面を更新してください。");
            transaction.markFailed(null, "SHIPPING_STOP_WITHDRAWN", "出荷中止の取りやめ", expiries.databaseNow());
            paymentTransactions.saveAndFlush(transaction);
        }

        @Transactional(readOnly = true)
        public View view(long orderId, long accountId) {
            var stops = jdbc.query("""
                    select timezone('Asia/Tokyo', created_at) created_at, admin_username, reason, authorization_kind,
                           preparation_kind, file_state, status, timezone('Asia/Tokyo', resolved_at) resolved_at,
                           withdrawn_by_username, withdrawal_reason
                    from order_shipping_stops where order_id = ? order by id
                    """, (r, n) -> new Stop(r.getObject("created_at", LocalDateTime.class), r.getString("admin_username"),
                    r.getString("reason"), r.getString("authorization_kind"), r.getString("preparation_kind"),
                    r.getString("file_state"), r.getString("status"), r.getObject("resolved_at", LocalDateTime.class),
                    r.getString("withdrawn_by_username"), r.getString("withdrawal_reason")), orderId);
            Order order = orders.findById(orderId).orElse(null);
            if (order == null || order.getShippingPreparationStartedAt() == null || order.getStatus() != OrderStatus.ORDERED
                    || order.getShippedAt() != null) {
                return new View(State.NONE, null, null, stops, false, false);
            }
            String fileState = fileState(orderId);
            boolean permitted = allowed(accountId);
            if (requested(orderId) != null) {
                return new View(State.REQUESTED, REQUESTED_MESSAGE, fileState, stops, false, permitted);
            }
            if (!permitted) return new View(State.NO_PERMISSION, NO_PERMISSION, fileState, stops, false, false);
            try {
                return new View(State.AVAILABLE, null, fileState, stops, target(orderId).expired(), true);
            } catch (ShippingStopException e) {
                return new View(State.BLOCKED, e.getMessage(), fileState, stops, false, true);
            }
        }

        private Order lockStoppable(Target expected) {
            Order order = orders.findByIdForUpdate(expected.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(expected.orderId()));
            if (order.getStatus() == OrderStatus.CANCELLED) return null;
            requireStoppable(order);
            guard.requireNoOrderChange(order);
            return order;
        }

        private void insertStop(Order order, PaymentCancellationStart start, long accountId, String username, String reason,
                String kind, String status) {
            jdbc.update("""
                    insert into order_shipping_stops(order_id,payment_id,transaction_id,authorization_kind,status,admin_account_id,
                        admin_username,reason,warehouse_contacted,preparation_kind,prepared_at,file_state,recording_xid,created_at)
                    values (?,?,?,?,?,?,?,?,true,'ADMINISTRATOR',localtimestamp,'NONE','0'::xid8,clock_timestamp())
                    """, order.getId(), start.paymentId(), start.transactionId(), kind, status, accountId, username, reason);
        }

        private static void requireSamePayment(PaymentCancellationStart start, Target expected) {
            if (start.paymentId() == null || start.paymentId() != expected.paymentId()
                    || !Objects.equals(start.providerPaymentId(), expected.providerPaymentId())) {
                throw new IllegalStateException("取消対象の決済が確認時から変わりました。画面を更新してください。");
            }
        }

        private static PaymentTransactionInitiator initiator(long accountId, String username, String reason) {
            return new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN, accountId, username, reason);
        }

        private String fileState(long orderId) {
            List<String> found = jdbc.queryForList("""
                    select f.state from warehouse_file_outputs f
                    where f.emergency_shipping_preparation_id in (select id from emergency_shipping_preparations where order_id = ?)
                       or f.batch_id in (select batch_id from warehouse_export_lines where order_id = ?)
                    order by f.id desc limit 1
                    """, String.class, orderId, orderId);
            return found.isEmpty() ? "NONE" : found.getFirst();
        }

        private static void requireStoppable(Order order) {
            if (order.getShippingPreparationStartedAt() == null || order.getStatus() != OrderStatus.ORDERED || order.getShippedAt() != null)
                throw new ShippingStopException("発送準備を開始し、まだ発送していない注文だけを出荷中止できます。");
        }
    }

    static final String NO_PERMISSION = "出荷中止は、緊急出荷の権限を持つ管理者だけが行えます。";
    static final String REQUESTED_MESSAGE = "PAY.JPに与信の取消を依頼し、結果を確認しています。取消が確認できると、注文は自動で取り消されます。";
}
