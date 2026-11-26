package com.example.ecsite.service.changeclosure;

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

/** Feature143-A/B: the records of closing an unfinished order change (V111). Sends and state changes commit before any HTTP. */
@Repository
public class ChangeFlowClosureStore {

    public enum Status { REQUESTED, SENDING, CLOSED, RELEASED, REJECTED }

    public enum ReleaseReason { AUTHORIZED, PROCESSING, UNREADABLE, CHANGED, STALE }

    public enum SendStatus { PENDING, ACCEPTED, REJECTED, UNCONFIRMED, ERROR, EXPIRED }

    /** accountId is null for the system. */
    public record Actor(Long accountId, String username, String note) {
        String type() { return accountId == null ? "SYSTEM" : "ADMIN"; }
    }

    public record Closure(long id, long orderId, UUID operationId, UUID admissionId, long paymentId, long transactionId, String flowId, String requestedBy,
            String note, Status status, ReleaseReason releaseReason, LocalDateTime createdAt, LocalDateTime completedAt) {
        public boolean open() { return status == Status.REQUESTED || status == Status.SENDING; }
    }

    public record Send(long id, long closureId, String idempotencyKey, SendStatus status, String errorCode,
            LocalDateTime createdAt, LocalDateTime completedAt) {}

    private static final String COLUMNS = "id,order_id,operation_id,admission_id,payment_id,transaction_id,provider_payment_flow_id,"
            + "requested_by_username,internal_note,status,release_reason,created_at,completed_at";
    private static final RowMapper<Closure> CLOSURE = (r, n) -> new Closure(r.getLong("id"), r.getLong("order_id"),
            r.getObject("operation_id", UUID.class), r.getObject("admission_id", UUID.class),
            r.getLong("payment_id"), r.getLong("transaction_id"), r.getString("provider_payment_flow_id"),
            r.getString("requested_by_username"), r.getString("internal_note"), Status.valueOf(r.getString("status")),
            r.getString("release_reason") == null ? null : ReleaseReason.valueOf(r.getString("release_reason")),
            r.getObject("created_at", LocalDateTime.class), r.getObject("completed_at", LocalDateTime.class));
    private static final String SEND_COLUMNS = "id,closure_id,idempotency_key,status,error_code,created_at,completed_at";
    private static final RowMapper<Send> SEND = (r, n) -> new Send(r.getLong("id"), r.getLong("closure_id"),
            r.getString("idempotency_key"), SendStatus.valueOf(r.getString("status")), r.getString("error_code"),
            r.getObject("created_at", LocalDateTime.class), r.getObject("completed_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ChangeFlowClosureStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** The change of an order the administrator may close. At most one change of an order is unfinished. */
    public Optional<UUID> closableOperation(long orderId) {
        return jdbc.query("select id from order_change_operations where order_id=? and execution_route='REAUTHORIZE' "
                + "and state in ('AUTHORIZING','REQUIRES_ACTION','MANUAL_REVIEW') and f143_change_closable(id) order by id",
                (r, n) -> r.getObject(1, UUID.class), orderId).stream().findFirst();
    }

    /** Empty when the change is not eligible. A second open closure of the same change is refused by the unique index. */
    public Optional<Closure> request(long orderId, UUID operationId, Actor actor) {
        return tx.execute(s -> {
            // The canonical lock refuses a stopped runtime, so it is only taken for a change that looks eligible.
            if (!closable(operationId)) return Optional.<Closure>empty();
            lock(orderId, operationId);
            return requestLocked(orderId, operationId, actor);
        });
    }

    /** Feature143-B: a change the automatic closure may take. */
    public record Due(long orderId, UUID operationId) {}

    static final Actor AUTOMATIC = new Actor(null, "SYSTEM", "自動終了");

    /** after counts from automatic_until; retryAfter is the wait after a closure of the same change ended without closing it. */
    private static final String DUE = "op.execution_route='REAUTHORIZE' and op.state in ('AUTHORIZING','REQUIRES_ACTION','MANUAL_REVIEW') "
            + "and c.automatic_until <= clock_timestamp() - ?::interval "
            + "and not exists(select 1 from order_change_flow_closures x where x.operation_id=op.id and (x.completed_at is null "
            + "or x.completed_at > timezone('Asia/Tokyo', clock_timestamp()) - ?::interval))";
    private static final String DUE_FROM = " from order_change_operations op join order_edit_admissions r on r.order_id=op.order_id "
            + "and r.submission_key=op.submission_key join order_edit_execution_controls c on c.admission_id=r.id where ";

    /** Feature143-B: due changes, the longest overdue first. Eligibility is decided again by requestDue. */
    public List<Due> dueOperations(Duration after, Duration retryAfter, int limit) {
        return jdbc.query("with due as materialized (select op.order_id,op.id,c.automatic_until" + DUE_FROM + DUE
                + ") select order_id,id from due where f143_change_closable(id) order by automatic_until,id limit ?",
                (r, n) -> new Due(r.getLong(1), r.getObject(2, UUID.class)), millis(after), millis(retryAfter), limit);
    }

    /** Feature143-B: the automatic request; being due is decided again under the locks, so two workers never both start one. */
    public Optional<Closure> requestDue(Due due, Duration after, Duration retryAfter) {
        return tx.execute(s -> {
            if (!closable(due.operationId())) return Optional.<Closure>empty();
            lock(due.orderId(), due.operationId());
            boolean stillDue = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1" + DUE_FROM + "op.id=? and op.order_id=? and "
                    + DUE + ")", Boolean.class, due.operationId(), due.orderId(), millis(after), millis(retryAfter)));
            return stillDue ? requestLocked(due.orderId(), due.operationId(), AUTOMATIC) : Optional.<Closure>empty();
        });
    }

    private static String millis(Duration value) { return value.toMillis() + " milliseconds"; }

    private Optional<Closure> requestLocked(long orderId, UUID operationId, Actor actor) {
        if (open(orderId).isPresent() || !closable(operationId)) return Optional.<Closure>empty();
        return jdbc.query("insert into order_change_flow_closures(order_id,operation_id,admission_id,payment_id,transaction_id,"
                + "provider_payment_flow_id,requested_by_type,requested_by_account_id,requested_by_username,internal_note,status,created_at) "
                + "select op.order_id,op.id,r.id,p.id,t.id,p.provider_payment_id,?,?,?,?,'REQUESTED',localtimestamp "
                + "from order_change_operations op join order_edit_admissions r on r.order_id=op.order_id and r.submission_key=op.submission_key "
                + "join payments p on p.id=op.new_payment_id join payment_transactions t on t.payment_id=p.id "
                + "where op.id=? and op.order_id=? returning " + COLUMNS, CLOSURE, actor.type(), actor.accountId(), actor.username(),
                actor.note(), operationId, orderId).stream().findFirst();
    }

    private boolean closable(UUID operationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select f143_change_closable(?)", Boolean.class, operationId));
    }

    /** Applies the canceled Payment Flow: V111 checks the evidence and ends the change, its stock and its admission together. */
    public void closeUnfinished(Closure closure, String observation) {
        tx.executeWithoutResult(s -> jdbc.queryForObject("select f143_close_unfinished(?,?::jsonb)", Boolean.class, closure.id(), observation));
    }

    /** The only step that permits a POST: committed before it, and only from REQUESTED with every condition unchanged. */
    public Optional<Send> startSending(Closure closure) {
        return tx.execute(s -> {
            lock(closure.orderId(), closure.operationId());
            if (!closable(closure.operationId()) || jdbc.update("update order_change_flow_closures set status='SENDING' "
                    + "where id=? and status='REQUESTED'", closure.id()) != 1) return Optional.<Send>empty();
            return Optional.of(insertSend(closure.id()));
        });
    }

    /** Another cancel with a new key, after the previous one was answered with an error or its key expired. */
    public Optional<Send> resend(Closure closure) {
        return tx.execute(s -> {
            lock(closure.orderId(), closure.operationId());
            var current = jdbc.query("select " + COLUMNS + " from order_change_flow_closures where id=? for update", CLOSURE,
                    closure.id()).getFirst();
            var latest = latestSend(closure.id());
            if (current.status() != Status.SENDING || latest.isPresent()
                    && latest.get().status() != SendStatus.ERROR && latest.get().status() != SendStatus.EXPIRED)
                return Optional.<Send>empty();
            return Optional.of(insertSend(closure.id()));
        });
    }

    private Send insertSend(long closureId) {
        return jdbc.queryForObject("insert into order_change_flow_closure_sends(closure_id,idempotency_key,status,created_at) "
                + "values (?,?,'PENDING',localtimestamp) returning " + SEND_COLUMNS, SEND, closureId, "f143-" + UUID.randomUUID());
    }

    /** Records an answer unless another worker already recorded a final one. */
    public void finishSend(long sendId, SendStatus status, String errorCode) {
        String code = errorCode != null && errorCode.matches("[A-Za-z0-9_]{1,100}") ? errorCode : null;
        tx.executeWithoutResult(s -> jdbc.update("update order_change_flow_closure_sends set status=?, error_code=? where id=? "
                + "and (status='PENDING' or (status='UNCONFIRMED' and ?<>'UNCONFIRMED'))",
                status.name(), code, sendId, status.name()));
    }

    public boolean release(long closureId, ReleaseReason reason) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.update("update order_change_flow_closures set status='RELEASED', release_reason=? "
                + "where id=? and status='REQUESTED'", reason.name(), closureId) == 1));
    }

    public boolean reject(long closureId) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.update("update order_change_flow_closures set status='REJECTED' "
                + "where id=? and status='SENDING'", closureId) == 1));
    }

    public Optional<Closure> open(long orderId) {
        return jdbc.query("select " + COLUMNS + " from order_change_flow_closures where order_id=? and status in ('REQUESTED','SENDING')",
                CLOSURE, orderId).stream().findFirst();
    }

    public boolean closed(long closureId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from order_change_flow_closures where id=? "
                + "and status='CLOSED')", Boolean.class, closureId));
    }

    public List<Closure> forOrder(long orderId) {
        return jdbc.query("select " + COLUMNS + " from order_change_flow_closures where order_id=? order by id desc", CLOSURE, orderId);
    }

    public List<Send> sends(long closureId) {
        return jdbc.query("select " + SEND_COLUMNS + " from order_change_flow_closure_sends where closure_id=? order by id", SEND, closureId);
    }

    public Optional<Send> latestSend(long closureId) {
        return jdbc.query("select " + SEND_COLUMNS + " from order_change_flow_closure_sends where closure_id=? order by id desc limit 1",
                SEND, closureId).stream().findFirst();
    }

    public List<Closure> openClosures(int limit) {
        return jdbc.query("select " + COLUMNS + " from order_change_flow_closures where status in ('REQUESTED','SENDING') "
                + "order by id limit ?", CLOSURE, limit);
    }

    public boolean closureOlderThan(long closureId, Duration age) {
        return olderThan("order_change_flow_closures", "created_at", closureId, age);
    }

    public boolean sendOlderThan(long sendId, Duration age) {
        return olderThan("order_change_flow_closure_sends", "created_at", sendId, age);
    }

    private boolean olderThan(String table, String column, long id, Duration age) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select " + column + " <= timezone('Asia/Tokyo', clock_timestamp()) - ?::interval "
                + "from " + table + " where id=?", Boolean.class, age.toMillis() + " milliseconds", id));
    }

    /** The canonical Feature125 order: runtime, order, operations, payments by id, transactions, admission, control. */
    private void lock(long orderId, UUID operationId) {
        jdbc.queryForObject("select f125_reauth_lock(?,?)", Object.class, orderId, operationId);
    }
}
