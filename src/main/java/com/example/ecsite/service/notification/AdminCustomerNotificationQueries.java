package com.example.ecsite.service.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature133-C: read-only administrator views of customer order notifications. Times are shown in Asia/Tokyo.
 * A chain is an original notification plus its manual resend copies; only the latest one of a chain can need action.
 */
@Service
@Transactional(readOnly = true)
public class AdminCustomerNotificationQueries {

    public static final int PAGE_SIZE = 50;
    public static final int MAX_RESENDS = 3;

    public enum Filter {
        ALL("すべて"), ACTION_REQUIRED("要対応（送信失敗・結果不明）"), OPEN("送信待ち・送信中"), SENT("送信済み"),
        EXHAUSTED("送信失敗・結果不明"), SKIPPED("送信対象外"), EXPIRED("期限切れ");
        private final String label;
        Filter(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Row(long id, long orderId, String eventType, String status, String closeReason, int attemptCount,
            boolean deliveryUncertain, LocalDateTime createdAt, LocalDateTime closedAt, Long resendOf, String username,
            boolean latestOfChain) {
        public String eventLabel() { return CustomerOrderNotificationLabels.event(eventType); }
        public String statusLabel() { return CustomerOrderNotificationLabels.status(status, closeReason); }
        public boolean actionRequired() { return latestOfChain && "EXHAUSTED".equals(status); }
        public boolean resend() { return resendOf != null; }
    }

    public record Attempt(int attemptNo, String result, String failureCode, String recipient, String subject, String body,
            LocalDateTime startedAt, LocalDateTime finishedAt) {
        public String resultLabel() { return CustomerOrderNotificationLabels.attempt(result); }
    }

    public record Resend(int requestNo, String actorUsername, String reason, boolean riskAcknowledged,
            LocalDateTime requestedAt, long copyId, String copyStatus, String copyCloseReason) {
        public String copyStatusLabel() { return CustomerOrderNotificationLabels.status(copyStatus, copyCloseReason); }
    }

    /** Whether the original of a chain may be resent now, and why not. */
    public record Eligibility(boolean allowed, String refusal, boolean riskAcknowledgementRequired, int resendCount) {}

    public record Detail(Row notification, Row original, List<Attempt> attempts, List<Row> chain, List<Resend> resends,
            Eligibility eligibility) {}

    public record Page(List<Row> rows, int page, boolean hasNext) {}

    private static final String SELECT = """
        select n.id,n.order_id,n.event_type,n.status,n.close_reason,n.attempt_count,n.delivery_uncertain,
               timezone('Asia/Tokyo',n.created_at) created_local,timezone('Asia/Tokyo',n.closed_at) closed_local,
               n.resend_of,u.username,
               not exists (select 1 from customer_order_notifications l
                           where l.resend_of=coalesce(n.resend_of,n.id) and l.id>n.id) latest
        from customer_order_notifications n join users u on u.id=n.user_id
        """;

    private final JdbcTemplate jdbc;

    public AdminCustomerNotificationQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(Filter filter, String eventType, Long orderId, int page) {
        var where = new StringBuilder(" where true");
        var args = new ArrayList<Object>();
        switch (filter == null ? Filter.ALL : filter) {
            case ACTION_REQUIRED -> where.append(" and n.status='EXHAUSTED' and not exists (select 1 from customer_order_notifications l"
                    + " where l.resend_of=coalesce(n.resend_of,n.id) and l.id>n.id)");
            case OPEN -> where.append(" and n.status in ('PENDING','CLAIMED','SENDING','RETRY_WAIT')");
            case SENT, EXHAUSTED, SKIPPED, EXPIRED -> { where.append(" and n.status=?"); args.add(filter.name()); }
            case ALL -> { }
        }
        if (eventType != null && CustomerOrderNotificationLabels.isEvent(eventType)) {
            where.append(" and n.event_type=?"); args.add(eventType);
        }
        if (orderId != null) { where.append(" and n.order_id=?"); args.add(orderId); }
        int safePage = Math.max(page, 0);
        args.add(PAGE_SIZE + 1); args.add((long) safePage * PAGE_SIZE);
        var rows = jdbc.query(SELECT + where + " order by n.id desc limit ? offset ?", this::row, args.toArray());
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new Page(hasNext ? rows.subList(0, PAGE_SIZE) : rows, safePage, hasNext);
    }

    public List<Row> byOrder(long orderId) {
        return jdbc.query(SELECT + " where n.order_id=? order by n.id", this::row, orderId);
    }

    /** Chains whose latest notification failed or may have failed: an administrator should look at them. */
    public long countActionRequired() {
        return jdbc.queryForObject("""
            select count(*) from customer_order_notifications n where n.status='EXHAUSTED'
              and not exists (select 1 from customer_order_notifications l where l.resend_of=coalesce(n.resend_of,n.id) and l.id>n.id)
            """, Long.class);
    }

    public Optional<Detail> detail(long id) {
        var found = jdbc.query(SELECT + " where n.id=?", this::row, id);
        if (found.isEmpty()) return Optional.empty();
        var notification = found.getFirst();
        long originalId = notification.resendOf() == null ? notification.id() : notification.resendOf();
        var chain = jdbc.query(SELECT + " where n.id=? or n.resend_of=? order by n.id", this::row, originalId, originalId);
        var attempts = jdbc.query("""
            select attempt_no,result,failure_code,recipient,subject,body,timezone('Asia/Tokyo',started_at),timezone('Asia/Tokyo',finished_at)
            from customer_order_notification_attempts where notification_id=? order by attempt_no
            """, (r, n) -> new Attempt(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
                r.getObject(7, LocalDateTime.class), r.getObject(8, LocalDateTime.class)), id);
        var resends = jdbc.query("""
            select r.request_no,r.actor_username,r.reason,r.risk_acknowledged,timezone('Asia/Tokyo',r.requested_at),c.id,c.status,c.close_reason
            from customer_order_notification_resends r join customer_order_notifications c on c.resend_request_id=r.id
            where r.notification_id=? order by r.request_no
            """, (r, n) -> new Resend(r.getInt(1), r.getString(2), r.getString(3), r.getBoolean(4),
                r.getObject(5, LocalDateTime.class), r.getLong(6), r.getString(7), r.getString(8)), originalId);
        return Optional.of(new Detail(notification, chain.getFirst(), attempts, chain, resends, eligibility(chain)));
    }

    /** Mirrors the V92 request guard, which re-checks it under the original's row lock. */
    static Eligibility eligibility(List<Row> chain) {
        var latest = chain.getLast();
        int resends = chain.size() - 1;
        boolean uncertain = chain.stream().anyMatch(Row::deliveryUncertain);
        String refusal = null;
        if (!List.of("EXHAUSTED", "SKIPPED", "EXPIRED").contains(latest.status()))
            refusal = "SENT".equals(latest.status()) ? "送信済みのため再送できません。"
                    : "送信待ち・送信中のため、まだ再送できません。";
        else if (resends >= MAX_RESENDS) refusal = "再送は1つの通知につき" + MAX_RESENDS + "回までです。";
        return new Eligibility(refusal == null, refusal, uncertain, resends);
    }

    private Row row(ResultSet r, int n) throws SQLException {
        return new Row(r.getLong("id"), r.getLong("order_id"), r.getString("event_type"), r.getString("status"),
                r.getString("close_reason"), r.getInt("attempt_count"), r.getBoolean("delivery_uncertain"),
                r.getObject("created_local", LocalDateTime.class), r.getObject("closed_local", LocalDateTime.class),
                r.getObject("resend_of", Long.class), r.getString("username"), r.getBoolean("latest"));
    }
}
