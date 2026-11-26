package com.example.ecsite.service.orderchange;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature130-A: read-only administrator view of orders whose shipping is held by order change work
 * that the system has not (yet) resolved after the 14:00 deadline / 14:05 final decision.
 *
 * "Held" mirrors the two shipping/capture gates exactly ({@code OrderPaymentGuard.requireNoOrderChange}):
 * an order change operation that is not COMPLETED/ABORTED, and an order-edit admission that
 * {@code OrderEditAdmissionGuard.blocks}. Ordinary in-flight work before the final decision time is not
 * listed. Every row is computed in a single statement with the database clock; nothing is written.
 */
@Service
@Transactional(readOnly = true)
public class AdminOrderHoldViewService {

    public enum Kind { OPERATION, ADMISSION }

    public enum Category {
        CHANGE_MANUAL_REVIEW(true, "注文変更：手動確認待ち",
                "システムでは解消できません。注文詳細で状態を確認してください（再与信が完了しなかった注文変更の終了や、旧与信取消の再照合ができる場合は、操作が表示されます）。"),
        LATE_ADOPTION_PENDING(false, "遅延成立：注文切替未完了",
                "旧与信の取消が決定済みのため、元の注文には戻しません。Webhook・照合による完了を待っています。"),
        NOT_ADOPTED_CANCELLING(false, "不成立：不要な新与信の取消待ち",
                "注文変更は不成立です。不要になった新与信の取消と在庫予約の解放を自動で進めています。"),
        FINAL_DECISION_OVERDUE(false, "最終判定待ち",
                "変更期限＋5分を過ぎていますが、最終判定がまだ記録されていません。最終判定スケジューラの稼働を確認してください。"),
        EDIT_REVIEW_REQUIRED(true, "一括編集受付：確認が必要",
                "一括編集の受付が確認待ちで止まっています。"),
        EDIT_RECOVERY_PENDING(false, "一括編集受付：自動再試行待ち",
                "一括編集の受付を自動で再試行しています（Feature125のruntimeが停止中は進みません）。"),
        EDIT_OPEN_OVERDUE(false, "一括編集受付：期限後も未終了",
                "変更期限＋5分を過ぎても一括編集の受付が終了していません。"),
        EDIT_INCONSISTENT(true, "一括編集受付：終了状態の不整合",
                "終了済みの受付の状態に不整合があり、出荷・売上確定を止めています。");

        private final boolean manual;
        private final String label;
        private final String guidance;

        Category(boolean manual, String label, String guidance) {
            this.manual = manual;
            this.label = label;
            this.guidance = guidance;
        }

        public boolean isManual() { return manual; }
        public String getLabel() { return label; }
        public String getGuidance() { return guidance; }
    }

    public record Item(long orderId, String orderStatus, Kind kind, UUID referenceId, Category category,
            String state, String reasonCode, String outcome, String shippingDisposition, String decisionReason,
            String admissionState, String linkedOperationState, String since, long elapsedMinutes, boolean stale) {

        /** An admission whose order change awaits manual review cannot progress automatically either. */
        public boolean manual() {
            return category.isManual() || (kind == Kind.ADMISSION && "MANUAL_REVIEW".equals(linkedOperationState));
        }

        /** Extra, state-specific guidance in addition to the category guidance. */
        public String note() {
            if (kind == Kind.ADMISSION && "MANUAL_REVIEW".equals(linkedOperationState)) {
                return "紐づく注文変更が手動確認待ち（MANUAL_REVIEW）のため、この受付は自動では進みません。注文変更の行を確認してください。";
            }
            if (category == Category.EDIT_REVIEW_REQUIRED && "ABORTED".equals(linkedOperationState)) {
                return "注文変更の金融処理は取消済み（ABORTED）ですが、受付が終了していないため出荷できません。"
                        + "不成立（NOT_ADOPTED）が確定している場合は照合処理が受付を自動で終了します。"
                        + "この表示が続く場合は、Feature125のruntimeの稼働状況とアプリのログ（not-adopted closure deferred）を確認し、開発担当へ連絡してください。";
            }
            return null;
        }
    }

    public record Summary(long holdOrders, long manualOrders, long staleOrders) {}

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Tokyo"));

    /**
     * Same decision table as {@code OrderEditAdmissionGuard.blocks} over an admission row aliased {@code a}.
     * Kept in lockstep; a test compares both for every state combination.
     */
    public static final String ADMISSION_BLOCKS = """
        (case
          when a.closed_at is null then true
          when a.admission_state='ACCEPTED' and a.execution_state='COMPLETED' then a.applied_operation_id is null
          when a.applied_operation_id is not null then true
          when a.admission_state='REJECTED_LATE' then coalesce(a.execution_state,'')<>'NOT_READY'
          else not (coalesce(a.admission_state,'') in ('RECORDED_UNVERIFIED','UNPROVEN','ACCEPTED')
                    and coalesce(a.execution_state,'') in ('CLOSED_UNAPPLIED','FAILED_UNAPPLIED'))
         end)""";

    private static final String SQL = """
        with n as (select clock_timestamp() as now_tz, timezone('Asia/Tokyo',clock_timestamp()) as now_local),
        ops as (
          select o.order_id, 'OPERATION'::text as kind, o.id as ref_id, o.state::text as state,
            o.financial_review_reason::text as reason_code, d.outcome::text as outcome,
            d.shipping_disposition::text as disposition, d.reason_code::text as decision_reason,
            null::text as admission_state, null::text as linked_state,
            case when o.state='MANUAL_REVIEW' then 'CHANGE_MANUAL_REVIEW'
                 when d.outcome='LATE_ADOPTION' then 'LATE_ADOPTION_PENDING'
                 when d.outcome='NOT_ADOPTED' then 'NOT_ADOPTED_CANCELLING'
                 else 'FINAL_DECISION_OVERDUE' end as category,
            (case when o.state='MANUAL_REVIEW' then o.updated_at
                  when d.operation_id is not null then d.final_at
                  else coalesce(r.change_deadline_at,o.deadline_at)+interval '5 minutes' end) at time zone 'Asia/Tokyo' as since
          from order_change_operations o
          join orders r on r.id=o.order_id
          left join order_change_final_decisions d on d.operation_id=o.id
          cross join n
          where o.state not in ('COMPLETED','ABORTED')
            and (o.state='MANUAL_REVIEW' or d.operation_id is not null
                 or n.now_local>=coalesce(r.change_deadline_at,o.deadline_at)+interval '5 minutes')
        ),
        adm as (
          select a.order_id, 'ADMISSION'::text, a.id, a.execution_state::text, a.reason_code::text,
            null::text, null::text, null::text, a.admission_state::text,
            (select o.state::text from order_change_operations o
              where o.order_id=a.order_id and o.submission_key=a.submission_key order by o.created_at desc, o.id limit 1),
            case when a.closed_at is not null then 'EDIT_INCONSISTENT'
                 when a.execution_state='REVIEW_REQUIRED' then 'EDIT_REVIEW_REQUIRED'
                 when a.execution_state='RECOVERY_PENDING' then 'EDIT_RECOVERY_PENDING'
                 else 'EDIT_OPEN_OVERDUE' end,
            case when a.closed_at is null and a.execution_state not in ('REVIEW_REQUIRED','RECOVERY_PENDING')
                 then a.deadline_at+interval '5 minutes' else a.updated_at end
          from order_edit_admissions a cross join n
          where %s
            and (a.closed_at is not null or a.execution_state in ('REVIEW_REQUIRED','RECOVERY_PENDING')
                 or n.now_tz>=a.deadline_at+interval '5 minutes')
        )
        select h.*, r.status::text as order_status,
          greatest(0, floor(extract(epoch from (n.now_tz-h.since))/60))::bigint as elapsed_minutes
        from (select * from ops union all select * from adm) h
        join orders r on r.id=h.order_id
        cross join n
        where (?::bigint is null or h.order_id=?)
        """.formatted(ADMISSION_BLOCKS);

    private final JdbcTemplate jdbc;
    private final Duration staleAfter;

    public AdminOrderHoldViewService(JdbcTemplate jdbc,
            @Value("${app.order-hold.stale-after:30m}") Duration staleAfter) {
        this.jdbc = Objects.requireNonNull(jdbc);
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("app.order-hold.stale-after must be positive");
        }
        this.staleAfter = staleAfter;
    }

    public long staleAfterMinutes() {
        return staleAfter.toMinutes();
    }

    /** All held items: administrator action first, then stalled items, then the longest waiting. */
    public List<Item> list() {
        return query(null);
    }

    public List<Item> listForOrder(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("Invalid order identifier");
        return query(orderId);
    }

    public Summary summary() {
        return summarize(list());
    }

    public static Summary summarize(List<Item> items) {
        var all = new HashSet<Long>();
        var manual = new HashSet<Long>();
        var stale = new HashSet<Long>();
        for (var item : items) {
            all.add(item.orderId());
            if (item.manual()) manual.add(item.orderId());
            if (item.stale()) stale.add(item.orderId());
        }
        return new Summary(all.size(), manual.size(), stale.size());
    }

    private List<Item> query(Long orderId) {
        long threshold = staleAfterMinutes();
        return jdbc.query(SQL, (r, n) -> {
            long elapsed = r.getLong("elapsed_minutes");
            Timestamp since = r.getTimestamp("since");
            return new Item(r.getLong("order_id"), r.getString("order_status"), Kind.valueOf(r.getString("kind")),
                    r.getObject("ref_id", UUID.class), Category.valueOf(r.getString("category")),
                    r.getString("state"), r.getString("reason_code"), r.getString("outcome"),
                    r.getString("disposition"), r.getString("decision_reason"), r.getString("admission_state"),
                    r.getString("linked_state"), since == null ? "" : DISPLAY.format(since.toInstant()),
                    elapsed, elapsed >= threshold);
        }, orderId, orderId).stream().sorted(ORDER).toList();
    }

    static final Comparator<Item> ORDER = Comparator.comparing(Item::manual).reversed()
            .thenComparing(Comparator.comparing(Item::stale).reversed())
            .thenComparing(Comparator.comparingLong(Item::elapsedMinutes).reversed())
            .thenComparingLong(Item::orderId)
            .thenComparing(i -> i.referenceId().toString());
}
