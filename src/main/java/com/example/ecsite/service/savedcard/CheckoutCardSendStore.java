package com.example.ecsite.service.savedcard;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Feature139-B (V106): what this site sent to the checkout Payment Flow of an order. Each method is its own short
 * transaction; PAY.JP is called between them. A send is started while holding the order row and then the payment
 * row ({@code FOR UPDATE}, the documented lock order) and only when the authorization has no send in flight.
 */
@Repository
public class CheckoutCardSendStore {

    public enum SendType { FLOW_UPDATE, CONFIRM }

    public enum Purpose { SAVED_CARD, REGISTER_NEW_CARD }

    public enum Status { PENDING, SUCCEEDED, FAILED, UNCONFIRMED }

    public record Send(long id, long userId, long orderId, long paymentId, long transactionId, SendType type,
            Purpose purpose, String flowId, String customerId, String paymentMethodId, String idempotencyKey,
            Status status, String errorCode, LocalDateTime createdAt, LocalDateTime completedAt) {}

    /** The authorization already has a send whose result is not known yet. */
    public static class BusyException extends RuntimeException {
        public BusyException() { super("A checkout card send is still being confirmed"); }
    }

    public static final int MAX_CONFIRMS = 3;

    private static final String COLUMNS = "id,user_id,order_id,payment_id,transaction_id,send_type,purpose,"
            + "provider_payment_flow_id,provider_customer_id,provider_payment_method_id,idempotency_key,status,error_code,"
            + "created_at,completed_at";

    private static final RowMapper<Send> ROW = (r, n) -> new Send(r.getLong("id"), r.getLong("user_id"),
            r.getLong("order_id"), r.getLong("payment_id"), r.getLong("transaction_id"),
            SendType.valueOf(r.getString("send_type")), Purpose.valueOf(r.getString("purpose")),
            r.getString("provider_payment_flow_id"), r.getString("provider_customer_id"),
            r.getString("provider_payment_method_id"), r.getString("idempotency_key"),
            Status.valueOf(r.getString("status")), r.getString("error_code"),
            r.getObject("created_at", LocalDateTime.class), r.getObject("completed_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CheckoutCardSendStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Send begin(long userId, long orderId, long paymentId, long transactionId, SendType type, Purpose purpose,
            String flowId, String customerId, String paymentMethodId) {
        return tx.execute(s -> {
            // Lock order: the order first, then its payment.
            if (jdbc.queryForList("select id from orders where id=? for update", Long.class, orderId).isEmpty()
                    || jdbc.queryForList("select id from payments where id=? and order_id=? for update", Long.class,
                            paymentId, orderId).isEmpty())
                throw new IllegalStateException("Order or payment missing");
            if (!inFlight(transactionId).isEmpty()) throw new BusyException();
            String key = "f139b-" + type.name().toLowerCase().replace('_', '-') + "-" + UUID.randomUUID();
            return jdbc.queryForObject("insert into checkout_card_sends(user_id,order_id,payment_id,transaction_id,send_type,"
                    + "purpose,provider_payment_flow_id,provider_customer_id,provider_payment_method_id,idempotency_key,status,"
                    + "created_at) values (?,?,?,?,?,?,?,?,?,?,'PENDING',localtimestamp) returning " + COLUMNS, ROW,
                    userId, orderId, paymentId, transactionId, type.name(), purpose.name(), flowId, customerId,
                    paymentMethodId, key);
        });
    }

    /** Records a result when the send is still in {@code expected}; empty when another request recorded it first. */
    public Optional<Send> finish(long sendId, Status expected, Status status, String errorCode) {
        if (status == Status.PENDING || expected == Status.SUCCEEDED || expected == Status.FAILED)
            throw new IllegalArgumentException("Invalid checkout card send transition");
        return tx.execute(s -> jdbc.query("update checkout_card_sends set status=?, error_code=? where id=? and status=? "
                + "returning " + COLUMNS, ROW, status.name(),
                status == Status.SUCCEEDED ? null : safeCode(errorCode), sendId, expected.name()).stream().findFirst());
    }

    public List<Send> forTransaction(long transactionId) {
        return jdbc.query("select " + COLUMNS + " from checkout_card_sends where transaction_id=? order by id", ROW, transactionId);
    }

    public List<Send> forPayment(long paymentId) {
        return jdbc.query("select " + COLUMNS + " from checkout_card_sends where payment_id=? order by id", ROW, paymentId);
    }

    public List<Send> inFlight(long transactionId) {
        return jdbc.query("select " + COLUMNS + " from checkout_card_sends where transaction_id=? "
                + "and status in ('PENDING','UNCONFIRMED') order by id", ROW, transactionId);
    }

    public boolean pendingOlderThan(long sendId, Duration age) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select created_at < timezone('Asia/Tokyo', clock_timestamp()) - ?::interval "
                + "from checkout_card_sends where id=?", Boolean.class, age.toMillis() + " milliseconds", sendId));
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_]{1,100}") ? code : null;
    }
}
