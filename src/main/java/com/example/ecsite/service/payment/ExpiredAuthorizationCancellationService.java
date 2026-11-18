package com.example.ecsite.service.payment;

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

/**
 * Feature135-B1: an administrator cancels an order whose card authorization has expired (design: docs/feature135).
 *
 * PAY.JP cancels an expired authorization by itself, so no cancel POST is ever sent here:
 * <ol>
 * <li>a read-only check finds the single AUTHORIZED payment whose recorded expiry (V93) has passed;</li>
 * <li>the Payment Flow is read outside any transaction and must already be canceled;</li>
 * <li>one transaction locks the Order, then its Payments in ID order, re-checks everything, records the CANCEL
 *     transaction, its V94 audit row, the payment and order cancellation, stock, history and customer email.</li>
 * </ol>
 * This is the only cancellation allowed after the order change deadline; V94 refuses any other.
 */
@Service
public class ExpiredAuthorizationCancellationService {
    private static final Logger log = LoggerFactory.getLogger(ExpiredAuthorizationCancellationService.class);

    public record Target(long orderId, long paymentId, String providerPaymentId) {}

    public enum Outcome { CANCELLED, ALREADY_CANCELLED }

    private final Transactions transactions;
    private final PaymentGateway gateway;

    public ExpiredAuthorizationCancellationService(Transactions transactions, PaymentGateway gateway) {
        this.transactions = transactions;
        this.gateway = gateway;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Outcome cancel(long orderId, long accountId, String username, String internalNote) {
        var target = transactions.target(orderId);
        if (target == null) return Outcome.ALREADY_CANCELLED;
        var state = gateway.retrievePaymentFlow(target.providerPaymentId());
        if (state == null || state.status() != PaymentFlowStatus.CANCELED
                || !target.providerPaymentId().equals(state.providerPaymentId())) {
            throw new AuthorizationExpiryException(
                    "PAY.JP上でまだ与信が取り消されていません。期限切れから取消まで30分ほどかかることがあります。時間をおいて再度お試しください。");
        }
        var outcome = transactions.apply(target, accountId, username, internalNote);
        log.info("Expired authorization cancellation: outcome={}, orderId={}, paymentId={}", outcome, orderId, target.paymentId());
        return outcome;
    }

    /** Whether the administrator order detail should offer this cancellation (no surrounding transaction). */
    @Transactional(propagation = Propagation.NEVER)
    public boolean available(long orderId) {
        try {
            return transactions.target(orderId) != null;
        } catch (RuntimeException notEligible) {
            return false;
        }
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

        /** {@code null} when the order is already cancelled; otherwise throws unless it is eligible. */
        @Transactional(readOnly = true)
        public Target target(long orderId) {
            Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
            if (order.getStatus() == OrderStatus.CANCELLED) return null;
            requireCancellableUnprepared(order);
            // Same condition as the V86 export gate; apply() re-checks with the full order-change guard under lock.
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from order_change_operations where order_id = ? and state not in ('COMPLETED','ABORTED'))
                        or f131_order_has_blocking_admission(?)
                    """, Boolean.class, orderId, orderId))) {
                throw new AuthorizationExpiryException("注文変更の処理が終わっていないため、この方法では取り消せません。");
            }
            List<Target> found = jdbc.query("""
                    select p.id, p.provider_payment_id from payments p
                    join payment_authorization_expiries e on e.payment_id = p.id
                    where p.order_id = ? and p.status = 'AUTHORIZED' and p.provider = 'PAYJP' and p.payment_method = 'CARD'
                      and e.expires_at <= timezone('Asia/Tokyo', clock_timestamp())
                    """, (r, n) -> new Target(orderId, r.getLong(1), r.getString(2)), orderId);
            if (found.size() != 1 || found.getFirst().providerPaymentId() == null || found.getFirst().providerPaymentId().isBlank()) {
                throw new AuthorizationExpiryException("与信期限切れの取消の対象になる決済がありません。");
            }
            return found.getFirst();
        }

        @Transactional
        public Outcome apply(Target expected, long accountId, String username, String internalNote) {
            Order order = orders.findByIdForUpdate(expected.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(expected.orderId()));
            if (order.getStatus() == OrderStatus.CANCELLED) return Outcome.ALREADY_CANCELLED;
            requireEligibleOrder(order);
            String note = internalNote == null || internalNote.isBlank() ? null : internalNote.trim();
            // Locks the Payments in ID order and re-validates them; refuses unless the recorded expiry has passed.
            var start = payments.startExpiredAuthorizationCancellation(order,
                    new PaymentTransactionInitiator(PaymentTransactionInitiatorType.ADMIN, accountId, username, note));
            if (start.paymentId() == null || start.paymentId() != expected.paymentId()
                    || !Objects.equals(start.providerPaymentId(), expected.providerPaymentId())) {
                throw new IllegalStateException("取消対象の決済が確認時から変わりました。画面を更新してください。");
            }
            expiries.recordExpiredCancellation(start.transactionId(), start.paymentId(), start.providerPaymentId());
            var applied = payments.applyCancellationResult(order, start.snapshot(),
                    new CancellationResult(CancellationResultStatus.CANCELLED, start.providerPaymentId()));
            if (applied != PaymentResultApplication.APPLIED) {
                throw new IllegalStateException("決済の取消を反映できませんでした: " + applied);
            }
            orderService.cancelOrderAfterPaymentCancellation(order, accountId, username, note);
            return Outcome.CANCELLED;
        }

        private void requireEligibleOrder(Order order) {
            requireCancellableUnprepared(order);
            guard.requireNoOrderChange(order);
        }

        private static void requireCancellableUnprepared(Order order) {
            if (!order.canCancel()) throw new AuthorizationExpiryException("注文受付中の注文だけを取り消せます。");
            if (order.getShippingPreparationStartedAt() != null)
                throw new AuthorizationExpiryException("発送準備を開始した注文は、この方法では取り消せません。");
        }
    }
}
