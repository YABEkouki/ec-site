package com.example.ecsite.service.warehouse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.service.warehouse.WarehouseExportHoldException.Reason;

/**
 * Feature131 C-3: hold history of the 14:05 export and its administrator view. Recording never decides anything
 * and never blocks the export: it runs in its own short transaction after the order's transaction has ended.
 */
@Component
public class WarehouseExportHoldStore {
    private static final Logger log = LoggerFactory.getLogger(WarehouseExportHoldStore.class);

    /** An order that is still waiting to be exported, with its most recent hold. Times are Asia/Tokyo wall time. */
    public record Held(long orderId, String orderStatus, Reason reason, LocalDate lastBusinessDate,
            LocalDate firstBusinessDate, int heldBatches, LocalDateTime recordedAt) {
        /** Held on two or more business dates: shipping is already late. */
        public boolean overdue() { return heldBatches >= 2; }
        public String label() { return WarehouseExportHoldStore.label(reason); }
        public String guidance() { return WarehouseExportHoldStore.guidance(reason); }
    }

    private final JdbcTemplate jdbc;

    public WarehouseExportHoldStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Best effort, one statement. Callers (the export facade and runner) run without a transaction, so it commits
     * on its own. A resumed pass keeps the first reason recorded for the batch.
     */
    @Transactional(propagation = Propagation.NEVER)
    public void recordQuietly(long batchId, long orderId, Reason reason) {
        try {
            jdbc.update("""
                insert into warehouse_export_holds(batch_id,order_id,reason) values (?,?,?)
                on conflict on constraint uq_warehouse_export_hold do nothing
                """, batchId, orderId, reason.name());
        } catch (RuntimeException failure) {
            log.warn("Warehouse export hold not recorded. batchId={}, orderId={}, reason={}", batchId, orderId, reason);
        }
    }

    /**
     * Orders whose latest export attempt held them and which are still not prepared (ORDERED/PAID). Once an order
     * is exported, prepared by an administrator or cancelled it disappears from this list.
     */
    @Transactional(readOnly = true)
    public List<Held> pending(Long orderId) {
        return jdbc.query("""
            with latest as (
              select distinct on (h.order_id) h.order_id,h.reason,h.recorded_at,b.business_date
              from warehouse_export_holds h join warehouse_export_batches b on b.id=h.batch_id
              where (?::bigint is null or h.order_id=?)
              order by h.order_id,b.business_date desc,h.id desc)
            select l.order_id,o.status,l.reason,l.recorded_at,l.business_date,
              (select count(*) from warehouse_export_holds x where x.order_id=l.order_id) as held_batches,
              (select min(b.business_date) from warehouse_export_holds x join warehouse_export_batches b on b.id=x.batch_id
                where x.order_id=l.order_id) as first_date
            from latest l join orders o on o.id=l.order_id
            where o.shipping_preparation_started_at is null and o.status in ('ORDERED','PAID')
            order by first_date,l.order_id
            """, (r, n) -> new Held(r.getLong("order_id"), r.getString("status"), Reason.valueOf(r.getString("reason")),
                r.getObject("business_date", LocalDate.class), r.getObject("first_date", LocalDate.class),
                r.getInt("held_batches"), r.getObject("recorded_at", LocalDateTime.class)), orderId, orderId);
    }

    public static String label(Reason reason) {
        return switch (reason) {
            case ORDER_CHANGE_UNRESOLVED -> "注文変更が未解決";
            case PAYMENT_NOT_READY -> "決済状態を確認できない";
            case NOT_DUE -> "出力時刻・期限の条件外";
            case NOT_EXPORTABLE_STATUS -> "注文状態が出力対象外";
            case MANUALLY_PREPARED -> "管理者が発送準備を開始済み";
            case DATABASE_GUARD -> "DBの安全検査で拒否";
            case FAILED -> "出力処理のエラー";
        };
    }

    public static String guidance(Reason reason) {
        return switch (reason) {
            case ORDER_CHANGE_UNRESOLVED -> "注文変更・一括編集の処理が終わっていません。上の「注文変更による出荷保留」に同じ注文があれば、その案内に従ってください。解消すれば翌日の出力で再判定します。";
            case PAYMENT_NOT_READY -> "処理中の決済、有効な与信が複数ある、または与信が確認できない状態です。注文詳細の決済状況を確認してください。決済が確定すれば翌日の出力で再判定します。";
            case NOT_DUE -> "正式受注前か、出力対象日より後の変更期限です。通常は翌日以降の出力で自動的に対象になります。";
            case NOT_EXPORTABLE_STATUS -> "出力処理中に注文状態が変わりました。注文詳細を確認してください。";
            case MANUALLY_PREPARED -> "管理者が発送準備を開始した注文は自動出力しません（緊急出荷として個別に対応してください）。";
            case DATABASE_GUARD -> "アプリの判定とDBの安全検査が一致しませんでした。開発担当へ連絡してください（アプリのログ：database guard）。";
            case FAILED -> "一時的なエラーの可能性があります。翌日の出力で再判定します。続く場合はアプリのログを確認し、開発担当へ連絡してください。";
        };
    }
}
