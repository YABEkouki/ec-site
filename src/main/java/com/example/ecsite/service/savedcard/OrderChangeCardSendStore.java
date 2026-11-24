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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Feature139-C (V107): what this site sent to the new Payment Flow of an order edit reauthorization. The update is
 * recorded inside the transaction that records the one-time confirmation start ({@link #insertUpdate}); the confirm
 * and the results are short transactions of their own. PAY.JP is called between them.
 */
@Repository
public class OrderChangeCardSendStore {

    public enum SendType { FLOW_UPDATE, CONFIRM }

    public enum Status { PENDING, SUCCEEDED, FAILED, UNCONFIRMED }

    public record Send(long id, long userId, long orderId, UUID operationId, UUID admissionId, long paymentId,
            long transactionId, SendType type, String flowId, String customerId, String paymentMethodId,
            String idempotencyKey, Status status, String errorCode, LocalDateTime createdAt, LocalDateTime completedAt) {}

    /** The order change already has a send whose result is not known yet. */
    public static class BusyException extends RuntimeException {
        public BusyException() { super("An order change card send is still being confirmed"); }
    }

    public static final int MAX_CONFIRMS = 2;

    private static final String COLUMNS = "id,user_id,order_id,operation_id,admission_id,payment_id,transaction_id,send_type,"
            + "provider_payment_flow_id,provider_customer_id,provider_payment_method_id,idempotency_key,status,error_code,"
            + "created_at,completed_at";

    private static final RowMapper<Send> ROW = (r, n) -> new Send(r.getLong("id"), r.getLong("user_id"),
            r.getLong("order_id"), r.getObject("operation_id", UUID.class), r.getObject("admission_id", UUID.class),
            r.getLong("payment_id"), r.getLong("transaction_id"), SendType.valueOf(r.getString("send_type")),
            r.getString("provider_payment_flow_id"), r.getString("provider_customer_id"),
            r.getString("provider_payment_method_id"), r.getString("idempotency_key"),
            Status.valueOf(r.getString("status")), r.getString("error_code"),
            r.getObject("created_at", LocalDateTime.class), r.getObject("completed_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OrderChangeCardSendStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** The saved card update, in the caller's transaction that already holds the reauthorization locks. */
    public Send insertUpdate(long userId, long orderId, UUID operationId, UUID admissionId, long paymentId,
            long transactionId, String flowId, String customerId, String paymentMethodId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("The saved card update is recorded with the confirmation start");
        return insert(userId, orderId, operationId, admissionId, paymentId, transactionId, SendType.FLOW_UPDATE, flowId,
                customerId, paymentMethodId);
    }

    /** The confirm after the update. Locks the execution control, the order and its payments (canonical order). */
    public Send beginConfirm(Send update) {
        return tx.execute(s -> {
            jdbc.queryForObject("select f125_reauth_lock(?,?)", Object.class, update.orderId(), update.operationId());
            if (jdbc.queryForList("select id from orders where id=? for update", Long.class, update.orderId()).isEmpty())
                throw new IllegalStateException("Order missing");
            jdbc.queryForList("select id from payments where order_id=? order by id for update", Long.class, update.orderId());
            if (!inFlight(update.operationId()).isEmpty()) throw new BusyException();
            return insert(update.userId(), update.orderId(), update.operationId(), update.admissionId(), update.paymentId(),
                    update.transactionId(), SendType.CONFIRM, update.flowId(), update.customerId(), update.paymentMethodId());
        });
    }

    private Send insert(long userId, long orderId, UUID operationId, UUID admissionId, long paymentId, long transactionId,
            SendType type, String flowId, String customerId, String paymentMethodId) {
        String key = "f139c-" + type.name().toLowerCase().replace('_', '-') + "-" + UUID.randomUUID();
        return jdbc.queryForObject("insert into order_change_card_sends(user_id,order_id,operation_id,admission_id,payment_id,"
                + "transaction_id,send_type,provider_payment_flow_id,provider_customer_id,provider_payment_method_id,"
                + "idempotency_key,status,created_at) values (?,?,?,?,?,?,?,?,?,?,?,'PENDING',localtimestamp) returning "
                + COLUMNS, ROW, userId, orderId, operationId, admissionId, paymentId, transactionId, type.name(), flowId,
                customerId, paymentMethodId, key);
    }

    /** Records a result when the send is still in {@code expected}; empty when another request recorded it first. */
    public Optional<Send> finish(long sendId, Status expected, Status status, String errorCode) {
        if (status == Status.PENDING || expected == Status.SUCCEEDED || expected == Status.FAILED)
            throw new IllegalArgumentException("Invalid order change card send transition");
        return tx.execute(s -> jdbc.query("update order_change_card_sends set status=?, error_code=? where id=? and status=? "
                + "returning " + COLUMNS, ROW, status.name(),
                status == Status.SUCCEEDED ? null : safeCode(errorCode), sendId, expected.name()).stream().findFirst());
    }

    public List<Send> forOperation(UUID operationId) {
        return jdbc.query("select " + COLUMNS + " from order_change_card_sends where operation_id=? order by id", ROW, operationId);
    }

    public List<Send> forAdmission(UUID admissionId) {
        return jdbc.query("select " + COLUMNS + " from order_change_card_sends where admission_id=? order by id", ROW, admissionId);
    }

    public List<Send> inFlight(UUID operationId) {
        return jdbc.query("select " + COLUMNS + " from order_change_card_sends where operation_id=? "
                + "and status in ('PENDING','UNCONFIRMED') order by id", ROW, operationId);
    }

    public boolean pendingOlderThan(long sendId, Duration age) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select created_at < timezone('Asia/Tokyo', clock_timestamp()) - ?::interval "
                + "from order_change_card_sends where id=?", Boolean.class, age.toMillis() + " milliseconds", sendId));
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_]{1,100}") ? code : null;
    }
}
