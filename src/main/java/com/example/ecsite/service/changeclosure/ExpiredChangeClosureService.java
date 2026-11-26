package com.example.ecsite.service.changeclosure;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.payjp.ExpiredFlowObservation;

/**
 * Feature144: closes a not-adopted owned order change whose new authorization expired at PAY.JP
 * (docs/feature144/expired-change-closure.md).
 *
 * Nothing is sent to PAY.JP: the new Payment Flow is read (outside any transaction) and must already be canceled by its
 * expiry. Only then does V113 (f144_close_expired) record the cancel, abort the change, return its stock and close its
 * admission in one transaction, re-checking every condition under the Feature125 locks.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ExpiredChangeClosureService {
    private static final Logger log = LoggerFactory.getLogger(ExpiredChangeClosureService.class);

    /** NOT_EXPIRED: PAY.JP does not (yet) report the new authorization as canceled by its expiry; nothing was written. */
    public enum Outcome { CLOSED, NOT_EXPIRED, UNREADABLE, CHANGED }

    public record Closure(UUID operationId, long paymentId, String requestedBy, String note, String expiredAt,
            String canceledAt, LocalDateTime recordedAt) {}

    private final JdbcTemplate jdbc;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;

    public ExpiredChangeClosureService(JdbcTemplate jdbc, PaymentGateway gateway, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** The change of an order the administrator may close. */
    public Optional<UUID> closableOperation(long orderId) {
        return jdbc.query("select id from order_change_operations where order_id=? and execution_route='REAUTHORIZE' "
                + "and state in ('AUTHORIZED','MANUAL_REVIEW') and f144_change_closable(id) order by id",
                (r, n) -> r.getObject(1, UUID.class), orderId).stream().findFirst();
    }

    public List<Closure> forOrder(long orderId) {
        return jdbc.query("select operation_id,new_payment_id,requested_by_username,internal_note,observation->>'expiredAt',"
                + "observation->>'canceledAt',timezone('Asia/Tokyo',recorded_at) from order_change_expired_closures where order_id=? "
                + "order by recorded_at desc", (r, n) -> new Closure(r.getObject(1, UUID.class), r.getLong(2), r.getString(3),
                r.getString(4), r.getString(5), r.getString(6), r.getObject(7, LocalDateTime.class)), orderId);
    }

    public Outcome close(long orderId, long accountId, String username, String note) {
        UUID operation = closableOperation(orderId).orElseThrow(() -> new ChangeFlowClosureException(
                "この注文には、新しい与信の期限切れで終了できる注文変更がありません。注文変更の状態と、一括編集の稼働状態（Feature125）をご確認ください。"));
        String flow = jdbc.queryForObject("select p.provider_payment_id from order_change_operations op join payments p "
                + "on p.id=op.new_payment_id and p.order_id=op.order_id where op.id=?", String.class, operation);
        ExpiredFlowObservation observed;
        try {
            observed = gateway.observeExpiredFlow(flow);
        } catch (RuntimeException unreadable) {
            return logged(orderId, Outcome.UNREADABLE);
        }
        if (observed == null || !flow.equals(observed.id())) return logged(orderId, Outcome.UNREADABLE);
        if (!observed.expired()) return logged(orderId, Outcome.NOT_EXPIRED);
        try {
            // false: another administrator closed this change first.
            tx.execute(s -> jdbc.queryForObject("select f144_close_expired(?,?::jsonb,?,?,?)", Boolean.class,
                    operation, evidence(observed), accountId, username, note));
            return logged(orderId, Outcome.CLOSED);
        } catch (DataAccessException refused) {
            log.warn("Expired order change was not closed: orderId={}, operationId={}", orderId, operation);
            return logged(orderId, Outcome.CHANGED);
        }
    }

    /** The reduced document V113 checks; no card data and no response body. */
    private static String evidence(ExpiredFlowObservation flow) {
        var document = new LinkedHashMap<String, Object>();
        document.put("flow", flow.id());
        document.put("status", flow.status());
        document.put("cancellationReason", flow.cancellationReason());
        document.put("amount", flow.amount());
        document.put("amountReceived", flow.amountReceived());
        document.put("amountCapturable", flow.amountCapturable());
        document.put("currency", flow.currency());
        document.put("captureMethod", flow.captureMethod());
        document.put("livemode", flow.livemode());
        document.put("expiredAt", flow.expiredAt() == null ? null : flow.expiredAt().toString());
        document.put("canceledAt", flow.canceledAt() == null ? null : flow.canceledAt().toString());
        document.put("order", flow.orderId());
        document.put("operation", flow.operationId());
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(document);
    }

    private static Outcome logged(long orderId, Outcome outcome) {
        log.info("Expired order change closure: outcome={}, orderId={}", outcome, orderId);
        return outcome;
    }
}
