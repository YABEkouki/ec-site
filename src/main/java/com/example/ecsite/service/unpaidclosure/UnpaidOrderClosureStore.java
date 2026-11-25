package com.example.ecsite.service.unpaidclosure;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Feature142-A: the records of closing an unpaid order (V110). Sends and state changes commit before any HTTP. */
@Repository
public class UnpaidOrderClosureStore {

    public enum Status { REQUESTED, SENDING, CLOSED, RELEASED, REJECTED }

    public enum ReleaseReason { AUTHORIZED, PROCESSING, UNREADABLE, CHANGED, STALE }

    public enum SendStatus { PENDING, ACCEPTED, REJECTED, UNCONFIRMED, ERROR, EXPIRED }

    /** accountId is null for the system. */
    public record Actor(Long accountId, String username, String note) {
        String type() { return accountId == null ? "SYSTEM" : "ADMIN"; }
    }

    public record Closure(long id, long orderId, long paymentId, long transactionId, String flowId, String requestedBy,
            String note, Status status, ReleaseReason releaseReason, LocalDateTime createdAt, LocalDateTime completedAt) {
        public boolean open() { return status == Status.REQUESTED || status == Status.SENDING; }
    }

    public record Send(long id, long closureId, String idempotencyKey, SendStatus status, String errorCode,
            LocalDateTime createdAt, LocalDateTime completedAt) {}

    private static final String COLUMNS = "id,order_id,payment_id,transaction_id,provider_payment_flow_id,"
            + "requested_by_username,internal_note,status,release_reason,created_at,completed_at";
    private static final RowMapper<Closure> CLOSURE = (r, n) -> new Closure(r.getLong("id"), r.getLong("order_id"),
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

    public UnpaidOrderClosureStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Shown to the administrator; re-decided under the locks when the closure is requested. */
    public boolean closable(long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select f142_unpaid_order_closable(?)", Boolean.class, orderId));
    }

    /** Empty when the order is not eligible. A second open closure of the same order is refused by the unique index. */
    public Optional<Closure> request(long orderId, Actor actor) {
        return tx.execute(s -> {
            lock(orderId);
            if (open(orderId).isPresent() || !closable(orderId)) return Optional.<Closure>empty();
            return jdbc.query("insert into unpaid_order_closures(order_id,payment_id,transaction_id,provider_payment_flow_id,"
                    + "requested_by_type,requested_by_account_id,requested_by_username,internal_note,status,created_at) "
                    + "select o.id,p.id,t.id,p.provider_payment_id,?,?,?,?,'REQUESTED',localtimestamp from orders o "
                    + "join payments p on p.id=o.active_payment_id join payment_transactions t on t.payment_id=p.id "
                    + "where o.id=? returning " + COLUMNS, CLOSURE, actor.type(), actor.accountId(), actor.username(),
                    actor.note(), orderId).stream().findFirst();
        });
    }

    /** The only step that permits a POST: committed before it, and only from REQUESTED with every condition unchanged. */
    public Optional<Send> startSending(Closure closure) {
        return tx.execute(s -> {
            lock(closure.orderId());
            if (!closable(closure.orderId()) || jdbc.update("update unpaid_order_closures set status='SENDING' "
                    + "where id=? and status='REQUESTED'", closure.id()) != 1) return Optional.<Send>empty();
            return Optional.of(insertSend(closure.id()));
        });
    }

    /** Another cancel with a new key, after the previous one was answered with an error or its key expired. */
    public Optional<Send> resend(Closure closure) {
        return tx.execute(s -> {
            lock(closure.orderId());
            var current = jdbc.query("select " + COLUMNS + " from unpaid_order_closures where id=? for update", CLOSURE,
                    closure.id()).getFirst();
            var latest = latestSend(closure.id());
            if (current.status() != Status.SENDING || latest.isPresent()
                    && latest.get().status() != SendStatus.ERROR && latest.get().status() != SendStatus.EXPIRED)
                return Optional.<Send>empty();
            return Optional.of(insertSend(closure.id()));
        });
    }

    private Send insertSend(long closureId) {
        return jdbc.queryForObject("insert into unpaid_order_closure_sends(closure_id,idempotency_key,status,created_at) "
                + "values (?,?,'PENDING',localtimestamp) returning " + SEND_COLUMNS, SEND, closureId, "f142-" + UUID.randomUUID());
    }

    /** Records an answer unless another worker already recorded a final one. */
    public void finishSend(long sendId, SendStatus status, String errorCode) {
        String code = errorCode != null && errorCode.matches("[A-Za-z0-9_]{1,100}") ? errorCode : null;
        tx.executeWithoutResult(s -> jdbc.update("update unpaid_order_closure_sends set status=?, error_code=? where id=? "
                + "and (status='PENDING' or (status='UNCONFIRMED' and ?<>'UNCONFIRMED'))",
                status.name(), code, sendId, status.name()));
    }

    public boolean release(long closureId, ReleaseReason reason) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.update("update unpaid_order_closures set status='RELEASED', release_reason=? "
                + "where id=? and status='REQUESTED'", reason.name(), closureId) == 1));
    }

    public boolean reject(long closureId) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.update("update unpaid_order_closures set status='REJECTED' "
                + "where id=? and status='SENDING'", closureId) == 1));
    }

    /** In the transaction that applies the canceled Payment Flow; V110 checks both at commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markClosed(long orderId) {
        jdbc.update("update unpaid_order_closures set status='CLOSED' where order_id=? and status in ('REQUESTED','SENDING')", orderId);
    }

    public Optional<Closure> open(long orderId) {
        return jdbc.query("select " + COLUMNS + " from unpaid_order_closures where order_id=? and status in ('REQUESTED','SENDING')",
                CLOSURE, orderId).stream().findFirst();
    }

    public boolean isOpen(long orderId) {
        return open(orderId).isPresent();
    }

    public boolean closed(long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from unpaid_order_closures where order_id=? "
                + "and status='CLOSED')", Boolean.class, orderId));
    }

    public List<Closure> forOrder(long orderId) {
        return jdbc.query("select " + COLUMNS + " from unpaid_order_closures where order_id=? order by id desc", CLOSURE, orderId);
    }

    public List<Send> sends(long closureId) {
        return jdbc.query("select " + SEND_COLUMNS + " from unpaid_order_closure_sends where closure_id=? order by id", SEND, closureId);
    }

    public Optional<Send> latestSend(long closureId) {
        return jdbc.query("select " + SEND_COLUMNS + " from unpaid_order_closure_sends where closure_id=? order by id desc limit 1",
                SEND, closureId).stream().findFirst();
    }

    public List<Closure> openClosures(int limit) {
        return jdbc.query("select " + COLUMNS + " from unpaid_order_closures where status in ('REQUESTED','SENDING') "
                + "order by id limit ?", CLOSURE, limit);
    }

    public boolean closureOlderThan(long closureId, Duration age) {
        return olderThan("unpaid_order_closures", "created_at", closureId, age);
    }

    public boolean sendOlderThan(long sendId, Duration age) {
        return olderThan("unpaid_order_closure_sends", "created_at", sendId, age);
    }

    private boolean olderThan(String table, String column, long id, Duration age) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select " + column + " <= timezone('Asia/Tokyo', clock_timestamp()) - ?::interval "
                + "from " + table + " where id=?", Boolean.class, age.toMillis() + " milliseconds", id));
    }

    /** Order first, then its payments in id order. */
    private void lock(long orderId) {
        jdbc.queryForList("select id from orders where id=? for update", Long.class, orderId);
        jdbc.queryForList("select id from payments where order_id=? order by id for update", Long.class, orderId);
    }
}
