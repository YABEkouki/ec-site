package com.example.ecsite.service.savedcard;

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
 * Feature139 (V105): saved card operation records. Each method is its own short transaction; PAY.JP is always
 * called between them, never inside. A new operation is started only while holding the users row lock and only
 * when the user has no PENDING / UNCONFIRMED operation (the partial unique index is the database guarantee).
 */
@Repository
public class SavedCardOperationStore {

    public enum Type { CUSTOMER_CREATE, SETUP_START, SETUP_COMPLETE, DETACH, SET_DEFAULT, ATTACH }

    public enum Reason { USER, DUPLICATE, LIMIT, DEFAULT_REPLACEMENT }

    public enum Status { PENDING, SUCCEEDED, FAILED, UNCONFIRMED }

    public record Operation(long id, long userId, Type type, Reason reason, String customerId, String setupFlowId,
            String paymentMethodId, String idempotencyKey, Status status, String errorCode,
            LocalDateTime createdAt, LocalDateTime completedAt) {}

    /** The user already has an operation whose result is not known yet. */
    public static class BusyException extends RuntimeException {
        public BusyException() { super("A saved card operation is still being confirmed"); }
    }

    private static final String COLUMNS = "id,user_id,operation_type,reason,provider_customer_id,provider_setup_flow_id,"
            + "provider_payment_method_id,idempotency_key,status,error_code,created_at,completed_at";

    private static final RowMapper<Operation> ROW = (r, n) -> new Operation(r.getLong("id"), r.getLong("user_id"),
            Type.valueOf(r.getString("operation_type")), Reason.valueOf(r.getString("reason")),
            r.getString("provider_customer_id"), r.getString("provider_setup_flow_id"),
            r.getString("provider_payment_method_id"), r.getString("idempotency_key"),
            Status.valueOf(r.getString("status")), r.getString("error_code"),
            r.getObject("created_at", LocalDateTime.class), r.getObject("completed_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SavedCardOperationStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Records a PENDING operation with a fresh idempotency key. Throws {@link BusyException} when one is in flight. */
    public Operation begin(long userId, Type type, Reason reason, String customerId, String paymentMethodId) {
        if (type == Type.SETUP_COMPLETE) throw new IllegalArgumentException("Use recordSetupCompletion");
        return tx.execute(s -> {
            lockUser(userId);
            if (!inFlight(userId).isEmpty()) throw new BusyException();
            String key = "f139-" + type.name().toLowerCase().replace('_', '-') + "-" + UUID.randomUUID();
            return jdbc.queryForObject("insert into saved_card_operations(user_id,operation_type,reason,provider_customer_id,"
                    + "provider_payment_method_id,idempotency_key,status,created_at) values (?,?,?,?,?,?,'PENDING',localtimestamp) "
                    + "returning " + COLUMNS, ROW, userId, type.name(), reason.name(), customerId, paymentMethodId, key);
        });
    }

    /**
     * Records a result when the operation is still in {@code expected}. Empty when another request already
     * recorded it (the guard trigger would reject a second transition).
     */
    public Optional<Operation> finish(long operationId, Status expected, Status status, String errorCode, String setupFlowId) {
        if (status == Status.PENDING || expected == Status.SUCCEEDED || expected == Status.FAILED)
            throw new IllegalArgumentException("Invalid saved card operation transition");
        return tx.execute(s -> jdbc.query("update saved_card_operations set status=?, error_code=?, "
                + "provider_setup_flow_id=coalesce(?, provider_setup_flow_id) where id=? and status=? returning " + COLUMNS, ROW,
                status.name(), status == Status.SUCCEEDED ? null : safeCode(errorCode), setupFlowId, operationId,
                expected.name()).stream().findFirst());
    }

    /** The reason of the succeeded DETACH of the card, if it was detached by this site. */
    public Optional<Reason> detachReason(long userId, String paymentMethodId) {
        return jdbc.query("select " + COLUMNS + " from saved_card_operations where operation_type='DETACH' and status='SUCCEEDED' "
                + "and user_id=? and provider_payment_method_id=? order by id desc", ROW, userId, paymentMethodId)
                .stream().map(Operation::reason).findFirst();
    }

    /**
     * Records that the browser completed a Setup Flow this user started. Returns the existing record when the
     * completion was already recorded (checked under the users row lock, so no unique violation is relied on).
     */
    public Operation recordSetupCompletion(long userId, String customerId, String setupFlowId, String paymentMethodId) {
        return tx.execute(s -> {
            lockUser(userId);
            var existing = jdbc.query("select " + COLUMNS + " from saved_card_operations where operation_type='SETUP_COMPLETE' "
                    + "and provider_setup_flow_id=?", ROW, setupFlowId);
            if (!existing.isEmpty()) {
                var found = existing.getFirst();
                if (found.userId() != userId) throw new IllegalStateException("Setup Flow belongs to another user");
                return found;
            }
            return jdbc.queryForObject("insert into saved_card_operations(user_id,operation_type,reason,provider_customer_id,"
                    + "provider_setup_flow_id,provider_payment_method_id,status,created_at) "
                    + "values (?,'SETUP_COMPLETE','USER',?,?,?,'SUCCEEDED',localtimestamp) returning " + COLUMNS, ROW,
                    userId, customerId, setupFlowId, paymentMethodId);
        });
    }

    /** The successful SETUP_START of this user for the Setup Flow, if any. */
    public Optional<Operation> startedSetup(long userId, String setupFlowId) {
        return jdbc.query("select " + COLUMNS + " from saved_card_operations where operation_type='SETUP_START' "
                + "and status='SUCCEEDED' and user_id=? and provider_setup_flow_id=?", ROW, userId, setupFlowId)
                .stream().findFirst();
    }

    public List<Operation> inFlight(long userId) {
        return jdbc.query("select " + COLUMNS + " from saved_card_operations where user_id=? "
                + "and status in ('PENDING','UNCONFIRMED') order by id", ROW, userId);
    }

    /** True when the PENDING row is old enough that its request can no longer be running. */
    public boolean pendingOlderThan(long operationId, java.time.Duration age) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select created_at < timezone('Asia/Tokyo', clock_timestamp()) - ?::interval "
                + "from saved_card_operations where id=?", Boolean.class, age.toMillis() + " milliseconds", operationId));
    }

    private void lockUser(long userId) {
        var locked = jdbc.queryForList("select id from users where id=? for update", Long.class, userId);
        if (locked.isEmpty()) throw new IllegalStateException("User missing");
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_]{1,100}") ? code : null;
    }
}
