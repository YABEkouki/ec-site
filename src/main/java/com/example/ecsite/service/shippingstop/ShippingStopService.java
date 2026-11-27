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
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import com.example.ecsite.service.payment.PaymentAuthorizationExpiryStore;
import com.example.ecsite.service.payment.PaymentResultApplication;
import com.example.ecsite.service.payment.PaymentService;

/**
 * Feature146-A: an administrator allowed to ship urgently stops the shipment of a prepared, unshipped order whose
 * card authorization has expired (docs/feature146/shipping-stop.md). Like Feature135 no cancel POST is sent:
 * the Payment Flow is read outside any transaction and must already be canceled; one transaction then records the
 * stop (V114), the expired cancellation (V94), the payment and order cancellation and the stock.
 * Orders whose authorization is still valid are handled by 146-B.
 */
@Service
public class ShippingStopService {
    private static final Logger log = LoggerFactory.getLogger(ShippingStopService.class);

    public enum Outcome { STOPPED, ALREADY_CANCELLED }

    /** What the order detail offers. */
    public enum State { NONE, AVAILABLE, NO_PERMISSION, VALID_AUTHORIZATION, BLOCKED }

    public record Target(long orderId, long paymentId, String providerPaymentId) {}

    public record Stop(LocalDateTime createdAt, String adminUsername, String reason, String authorizationKind,
            String preparationKind, String fileState, String status) {}

    public record View(State state, String message, String fileState, List<Stop> stops) {}

    private final Transactions transactions;
    private final PaymentGateway gateway;

    public ShippingStopService(Transactions transactions, PaymentGateway gateway) {
        this.transactions = transactions;
        this.gateway = gateway;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Outcome stop(long orderId, long accountId, String username, String reason, boolean warehouseContacted) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > 500) throw new ShippingStopException("出荷中止の理由を500文字以内で入力してください。");
        if (!warehouseContacted) throw new ShippingStopException("倉庫に出荷しないよう連絡したことを確認してください。");
        if (!transactions.allowed(accountId)) throw new ShippingStopException("出荷中止は、緊急出荷の権限を持つ管理者だけが行えます。");
        var target = transactions.target(orderId);
        if (target == null) return Outcome.ALREADY_CANCELLED;
        var state = gateway.retrievePaymentFlow(target.providerPaymentId());
        if (state == null || state.status() != PaymentFlowStatus.CANCELED
                || !target.providerPaymentId().equals(state.providerPaymentId())) {
            throw new ShippingStopException(
                    "PAY.JP上でまだ与信が取り消されていません。期限切れから取消まで30分ほどかかることがあります。時間をおいて再度お試しください。");
        }
        var outcome = transactions.apply(target, accountId, username, trimmed);
        log.info("Shipping stop: outcome={}, orderId={}, paymentId={}", outcome, orderId, target.paymentId());
        return outcome;
    }

    @Transactional(propagation = Propagation.NEVER)
    public View view(long orderId, long accountId) {
        return transactions.view(orderId, accountId);
    }

    @Service
    public static class Transactions {
        private final OrderRepository orders;
        private final PaymentService payments;
        private final OrderService orderService;
        private final OrderPaymentGuard guard;
        private final PaymentAuthorizationExpiryStore expiries;
        private final JdbcTemplate jdbc;

        public Transactions(OrderRepository orders, PaymentService payments, OrderService orderService,
                OrderPaymentGuard guard, PaymentAuthorizationExpiryStore expiries, JdbcTemplate jdbc) {
            this.orders = orders; this.payments = payments; this.orderService = orderService;
            this.guard = guard; this.expiries = expiries; this.jdbc = jdbc;
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
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from order_change_operations where order_id = ? and state not in ('COMPLETED','ABORTED'))
                        or f131_order_has_blocking_admission(?)
                    """, Boolean.class, orderId, orderId))) {
                throw new ShippingStopException("注文変更の処理が終わっていないため、出荷中止できません。");
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from warehouse_shipment_import_lines where order_id = ? and state in ('PENDING','CAPTURE_UNCONFIRMED'))
                        or exists(select 1 from payment_transactions t join payments p on p.id = t.payment_id
                                  where p.order_id = ? and t.transaction_type = 'CAPTURE' and t.status = 'PENDING')
                    """, Boolean.class, orderId, orderId))) {
                throw new ShippingStopException("出荷・売上確定の処理中のため、出荷中止できません。");
            }
            List<Long> active = jdbc.queryForList("""
                    select p.id from payments p join orders o on o.id = p.order_id
                    where o.id = ? and p.status = 'AUTHORIZED' and p.provider = 'PAYJP' and p.payment_method = 'CARD'
                      and (o.active_payment_id is null or o.active_payment_id = p.id)
                    """, Long.class, orderId);
            if (active.size() != 1) throw new ShippingStopException("出荷中止の対象になる与信済みのカード決済がありません。");
            List<Target> expired = jdbc.query("""
                    select p.id, p.provider_payment_id from payments p
                    join payment_authorization_expiries e on e.payment_id = p.id
                    where p.id = ? and e.expires_at <= timezone('Asia/Tokyo', clock_timestamp())
                    """, (r, n) -> new Target(orderId, r.getLong(1), r.getString(2)), active.getFirst());
            if (expired.isEmpty()) throw new ShippingStopException(VALID_AUTHORIZATION);
            if (expired.getFirst().providerPaymentId() == null || expired.getFirst().providerPaymentId().isBlank())
                throw new ShippingStopException("出荷中止の対象になる与信済みのカード決済がありません。");
            return expired.getFirst();
        }

        @Transactional
        public Outcome apply(Target expected, long accountId, String username, String reason) {
            Order order = orders.findByIdForUpdate(expected.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(expected.orderId()));
            if (order.getStatus() == OrderStatus.CANCELLED) return Outcome.ALREADY_CANCELLED;
            requireStoppable(order);
            guard.requireNoOrderChange(order);
            // Locks the Payments in ID order and re-validates them; refuses unless the recorded expiry has passed.
            var start = payments.startExpiredAuthorizationCancellation(order,
                    new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN, accountId, username, reason));
            if (start.paymentId() == null || start.paymentId() != expected.paymentId()
                    || !Objects.equals(start.providerPaymentId(), expected.providerPaymentId())) {
                throw new IllegalStateException("取消対象の決済が確認時から変わりました。画面を更新してください。");
            }
            jdbc.update("""
                    insert into order_shipping_stops(order_id,payment_id,transaction_id,authorization_kind,status,admin_account_id,
                        admin_username,reason,warehouse_contacted,preparation_kind,prepared_at,file_state,recording_xid,created_at)
                    values (?,?,?,'EXPIRED','COMPLETED',?,?,?,true,'ADMINISTRATOR',localtimestamp,'NONE','0'::xid8,clock_timestamp())
                    """, order.getId(), start.paymentId(), start.transactionId(), accountId, username, reason);
            expiries.recordExpiredCancellation(start.transactionId(), start.paymentId(), start.providerPaymentId());
            var applied = payments.applyCancellationResult(order, start.snapshot(),
                    new CancellationResult(CancellationResultStatus.CANCELLED, start.providerPaymentId()));
            if (applied != PaymentResultApplication.APPLIED) {
                throw new IllegalStateException("決済の取消を反映できませんでした: " + applied);
            }
            orderService.cancelOrderForShippingStop(order, accountId, username, "出荷中止：" + reason);
            return Outcome.STOPPED;
        }

        @Transactional(readOnly = true)
        public View view(long orderId, long accountId) {
            var stops = jdbc.query("""
                    select timezone('Asia/Tokyo', created_at) created_at, admin_username, reason, authorization_kind,
                           preparation_kind, file_state, status
                    from order_shipping_stops where order_id = ? order by id
                    """, (r, n) -> new Stop(r.getObject("created_at", LocalDateTime.class), r.getString("admin_username"),
                    r.getString("reason"), r.getString("authorization_kind"), r.getString("preparation_kind"),
                    r.getString("file_state"), r.getString("status")), orderId);
            Order order = orders.findById(orderId).orElse(null);
            if (order == null || order.getShippingPreparationStartedAt() == null || order.getStatus() != OrderStatus.ORDERED
                    || order.getShippedAt() != null) {
                return new View(State.NONE, null, null, stops);
            }
            String fileState = fileState(orderId);
            if (!allowed(accountId)) return new View(State.NO_PERMISSION, "出荷中止は、緊急出荷の権限を持つ管理者だけが行えます。", fileState, stops);
            try {
                target(orderId);
                return new View(State.AVAILABLE, null, fileState, stops);
            } catch (ShippingStopException e) {
                return new View(VALID_AUTHORIZATION.equals(e.getMessage()) ? State.VALID_AUTHORIZATION : State.BLOCKED,
                        e.getMessage(), fileState, stops);
            }
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

    static final String VALID_AUTHORIZATION = "与信の期限がまだ切れていません。与信が有効な注文の出荷中止は、次の機能（Feature146-B）で対応します。";
}
