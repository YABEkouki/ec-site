package com.example.ecsite.service.warehouse;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature137: the content of the warehouse files, read only from rows that never change.
 * <ul>
 * <li>14:05 batch: the V101 snapshot of the export lines of a SEALED batch. Lines written before V101 have no
 * snapshot (old format) and are never written as files.</li>
 * <li>Emergency shipping: the order prepared by its emergency record. Its items, charges and amounts are frozen
 * once preparation has started (V98), and the detail rows come from the same function as the snapshot
 * ({@code f137_order_file_lines}, V101).</li>
 * </ul>
 * The content is validated when it is built ({@link WarehouseFileFormat.Content}).
 */
@Service
public class WarehouseFileSource {

    public static class NotWritableException extends RuntimeException {
        public NotWritableException(String message) { super(message); }
    }

    private final JdbcTemplate jdbc;

    public WarehouseFileSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public WarehouseFileFormat.Content forBatch(long batchId) {
        var batch = jdbc.query("select business_date,state,line_count from warehouse_export_batches where id=?",
                (r, n) -> new Object[] {r.getObject("business_date", LocalDate.class), r.getString("state"), r.getObject("line_count")},
                batchId);
        if (batch.isEmpty()) throw new NotWritableException("倉庫出力バッチが見つかりません。");
        if (!"SEALED".equals(batch.getFirst()[1])) throw new NotWritableException("出力処理が完了していないバッチはファイルにできません。");
        var legacy = jdbc.queryForObject("select count(*) from warehouse_export_lines where batch_id=? and file_lines is null",
                Integer.class, batchId);
        if (legacy != null && legacy > 0) throw new NotWritableException("V101 より前の出力行（旧形式）を含むバッチはファイルにできません。");
        var headers = jdbc.query("""
                select order_id,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,
                  shipping_phone,total_amount,tax_amount
                from warehouse_export_lines where batch_id=? order by order_id
                """, (r, n) -> header(r, r.getLong("order_id")), batchId);
        if (headers.size() != (Integer) batch.getFirst()[2]) throw new IllegalStateException("倉庫出力バッチの行数が一致しません。");
        var details = jdbc.query("""
                select l.order_id,(e->>'lineNo')::int as line_no,(e->>'productId')::bigint as product_id,
                  e->>'productName' as product_name,(e->>'taxRate')::numeric as tax_rate,(e->>'unitPrice')::int as unit_price,
                  (e->>'quantity')::int as quantity,(e->>'subtotal')::int as subtotal,(e->>'taxAmount')::int as tax_amount
                from warehouse_export_lines l cross join lateral jsonb_array_elements(l.file_lines) as e
                where l.batch_id=? order by l.order_id,(e->>'lineNo')::int
                """, (r, n) -> detail(r, r.getLong("order_id")), batchId);
        return new WarehouseFileFormat.Content(String.valueOf(batchId), (LocalDate) batch.getFirst()[0], headers, details);
    }

    @Transactional(readOnly = true)
    public WarehouseFileFormat.Content forEmergency(long emergencyRecordId) {
        var found = jdbc.query("""
                select o.id as order_id,e.prepared_at,o.shipping_name,o.shipping_postal_code,o.shipping_prefecture,o.shipping_city,
                  o.shipping_address_line,o.shipping_phone,o.total_amount,o.tax_amount
                from emergency_shipping_preparations e join orders o on o.id=e.order_id
                where e.id=? and o.shipping_preparation_started_at is not distinct from e.prepared_at
                  and o.shipping_preparation_started_by_account_id is not distinct from e.admin_account_id
                  and o.content_revision = e.content_revision
                """, (r, n) -> new Object[] {header(r, r.getLong("order_id")), r.getObject("prepared_at", LocalDateTime.class)},
                emergencyRecordId);
        if (found.isEmpty()) throw new NotWritableException("緊急出荷として発送準備を開始した注文が見つかりません。");
        var header = (WarehouseFileFormat.HeaderRow) found.getFirst()[0];
        var details = jdbc.query("""
                select line_no,product_id,product_name,tax_rate,unit_price,quantity,subtotal,tax_amount
                from f137_order_file_lines(?) order by line_no
                """, (r, n) -> detail(r, header.orderId()), header.orderId());
        return new WarehouseFileFormat.Content("E" + emergencyRecordId,
                ((LocalDateTime) found.getFirst()[1]).toLocalDate(), List.of(header), details);
    }

    private static WarehouseFileFormat.HeaderRow header(ResultSet r, long orderId) throws SQLException {
        return new WarehouseFileFormat.HeaderRow(orderId, r.getString("shipping_name"), r.getString("shipping_postal_code"),
                r.getString("shipping_prefecture"), r.getString("shipping_city"), r.getString("shipping_address_line"),
                r.getString("shipping_phone"), r.getInt("total_amount"), r.getInt("tax_amount"));
    }

    private static WarehouseFileFormat.DetailRow detail(ResultSet r, long orderId) throws SQLException {
        return new WarehouseFileFormat.DetailRow(orderId, r.getInt("line_no"), (Long) r.getObject("product_id", Long.class),
                r.getString("product_name"), r.getObject("tax_rate", BigDecimal.class), r.getInt("unit_price"),
                r.getInt("quantity"), r.getInt("subtotal"), r.getInt("tax_amount"));
    }
}
