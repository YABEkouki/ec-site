package com.example.ecsite.service.warehouse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Feature132-B: read-only views of shipped CSV imports for the administrator screens. */
@Service
@Transactional(readOnly = true)
public class WarehouseShipmentImportQueries {

    public record Import(long id, String fileName, int lineCount, long uploadedByAccountId, String uploadedByUsername,
            LocalDateTime uploadedAt) {}

    /** Line counts per outcome; {@code actionRequired} = rejected + capture failed. */
    public record Listed(Import importRecord, int pending, int shipped, int unconfirmed, int alreadyShipped,
            int actionRequired) {}

    public record Line(long id, int lineNo, String lineText, Long batchId, Long orderId, LocalDate csvShippedOn,
            String carrier, String trackingNumber, WarehouseShipmentImportLineState state,
            WarehouseShipmentImportReason reason, int attempts, LocalDateTime processedAt) {}

    private final JdbcTemplate jdbc;

    public WarehouseShipmentImportQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Listed> list(int limit) {
        return jdbc.query("""
            select i.id,i.file_name,i.line_count,i.uploaded_by_account_id,i.uploaded_by_username,i.uploaded_at,
              count(*) filter (where l.state='PENDING') as pending,
              count(*) filter (where l.state='SHIPPED') as shipped,
              count(*) filter (where l.state='CAPTURE_UNCONFIRMED') as unconfirmed,
              count(*) filter (where l.state='ALREADY_SHIPPED') as already,
              count(*) filter (where l.state in ('REJECTED','CAPTURE_FAILED')) as action
            from warehouse_shipment_imports i left join warehouse_shipment_import_lines l on l.import_id=i.id
            group by i.id order by i.id desc limit ?
            """, (r, n) -> new Listed(importRecord(r), r.getInt("pending"), r.getInt("shipped"), r.getInt("unconfirmed"),
                r.getInt("already"), r.getInt("action")), limit);
    }

    public Optional<Import> find(long id) {
        return jdbc.query("select * from warehouse_shipment_imports where id=?", (r, n) -> importRecord(r), id).stream().findFirst();
    }

    public List<Line> lines(long importId) {
        return jdbc.query("select * from warehouse_shipment_import_lines where import_id=? order by line_no", (r, n) -> new Line(
                r.getLong("id"), r.getInt("line_no"), r.getString("line_text"), r.getObject("batch_id", Long.class),
                r.getObject("order_id", Long.class), r.getObject("csv_shipped_on", LocalDate.class), r.getString("carrier"),
                r.getString("tracking_number"), WarehouseShipmentImportLineState.valueOf(r.getString("state")),
                r.getString("reason") == null ? null : WarehouseShipmentImportReason.valueOf(r.getString("reason")),
                r.getInt("attempts"), r.getObject("processed_at", LocalDateTime.class)), importId);
    }

    private static Import importRecord(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Import(r.getLong("id"), r.getString("file_name"), r.getInt("line_count"), r.getLong("uploaded_by_account_id"),
                r.getString("uploaded_by_username"), r.getObject("uploaded_at", LocalDateTime.class));
    }
}
