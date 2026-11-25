package com.example.ecsite.service.unpaidclosure;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Feature142-B: read models of the unpaid order list and the dashboard count. Read only.
 * An unpaid order is ORDERED under AUTHORIZED_ACCEPTANCE without a formal acceptance. A closure is stuck when the
 * order's latest one was rejected or has been sending for ten minutes.
 */
@Component
public class AdminUnpaidOrderQueries {
    public static final int PAGE_SIZE = 50;

    public enum View {
        ALL("すべて"), CLOSABLE("終了できる"), STUCK("終了処理が止まっている");
        private final String label;
        View(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public record Row(long orderId, LocalDateTime orderedAt, int totalAmount, String paymentStatus, boolean closable,
            String closureStatus, String requestedBy, LocalDateTime closureCreatedAt, String sendStatus, int sendCount,
            boolean stuck) {
        public String getClosureLabel() {
            if (closureStatus == null) return "なし";
            return switch (closureStatus) {
                case "REQUESTED" -> "受付";
                case "SENDING" -> "取消結果を確認中";
                case "REJECTED" -> "PAY.JPが取消を拒否";
                case "RELEASED" -> "中止（取消は未送信）";
                default -> closureStatus;
            };
        }
        public String getPaymentLabel() {
            if (paymentStatus == null) return "決済なし";
            return switch (paymentStatus) {
                case "PENDING" -> "未完了";
                case "REQUIRES_ACTION" -> "本人認証待ち";
                default -> paymentStatus;
            };
        }
    }

    public record Result(List<Row> rows, int page, long total, boolean hasNext) {}

    public record Summary(long unpaid, long stuck) {}

    /** Every unpaid order; f142_unpaid_order_closable is the single eligibility rule (V110). */
    private static final String UNPAID = """
            with base as materialized (
                select o.id,o.ordered_at,o.total_amount,p.status as payment_status,
                    c.id as closure_id,c.status as closure_status,c.requested_by_username,c.created_at as closure_created_at
                from orders o
                left join payments p on p.id=o.active_payment_id
                left join lateral (select * from unpaid_order_closures x where x.order_id=o.id order by x.id desc limit 1) c on true
                where o.status='ORDERED' and o.acceptance_mode='AUTHORIZED_ACCEPTANCE' and o.accepted_at is null
            ), unpaid as (
                select b.*, coalesce(b.closure_status='REJECTED' or (b.closure_status='SENDING'
                    and b.closure_created_at <= timezone('Asia/Tokyo',clock_timestamp())-?::interval),false) as stuck
                from base b
            )
            """;

    private final JdbcTemplate jdbc;

    private final String stuckAfter;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminUnpaidOrderQueries(JdbcTemplate jdbc) {
        this(jdbc, java.time.Duration.ofMinutes(10));
    }

    AdminUnpaidOrderQueries(JdbcTemplate jdbc, java.time.Duration stuckAfter) {
        this.jdbc = jdbc;
        this.stuckAfter = stuckAfter.toMillis() + " milliseconds";
    }

    public Result list(View view, int page) {
        int safePage = Math.max(page, 0);
        String closable = "(coalesce(u.closure_status,'') not in ('REQUESTED','SENDING') and f142_unpaid_order_closable(u.id))";
        String where = view == View.CLOSABLE ? " where " + closable : view == View.STUCK ? " where u.stuck" : "";
        long total = jdbc.queryForObject(UNPAID + "select count(*) from unpaid u" + where, Long.class, stuckAfter);
        List<Row> rows = jdbc.query(UNPAID + "select u.*," + closable + " as closable,"
                + "(select s.status from unpaid_order_closure_sends s where s.closure_id=u.closure_id order by s.id desc limit 1) as send_status,"
                + "(select count(*) from unpaid_order_closure_sends s where s.closure_id=u.closure_id)::int as send_count "
                + "from unpaid u" + where + " order by u.ordered_at,u.id limit ? offset ?",
                (r, n) -> new Row(r.getLong("id"), r.getObject("ordered_at", LocalDateTime.class), r.getInt("total_amount"),
                        r.getString("payment_status"), r.getBoolean("closable"), r.getString("closure_status"),
                        r.getString("requested_by_username"), r.getObject("closure_created_at", LocalDateTime.class),
                        r.getString("send_status"), r.getInt("send_count"), r.getBoolean("stuck")),
                stuckAfter, PAGE_SIZE, (long) safePage * PAGE_SIZE);
        return new Result(rows, safePage, total, (long) (safePage + 1) * PAGE_SIZE < total);
    }

    public Summary summary() {
        return jdbc.queryForObject(UNPAID + "select count(*),count(*) filter (where stuck) from unpaid",
                (r, n) -> new Summary(r.getLong(1), r.getLong(2)), stuckAfter);
    }
}
