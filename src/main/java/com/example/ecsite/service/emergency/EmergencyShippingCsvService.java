package com.example.ecsite.service.emergency;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.warehouse.WarehouseExportCsvService;

/**
 * Feature136-C2: the warehouse CSV of one emergency-shipped order, in the same format as the 14:05 warehouse export
 * (Feature131). The batch ID column is 'E' + the emergency record id and the business date is the date of the
 * emergency operation. The rows come from the order, which is frozen once preparation has started (V98), so every
 * download is byte-identical; each download is recorded with its SHA-256 (V100), and V100 fixes the download time
 * and the file name ({@code emergency-shipping-<orderId>-<yyyyMMdd-HHmmss>.csv}) by the database clock.
 * Every administrator may download.
 */
@Service
public class EmergencyShippingCsvService {

    public record Download(String filename, byte[] content, int rowCount) {}

    public record DownloadRecord(long id, String adminUsername, LocalDateTime downloadedAt, int rowCount, String filename) {}

    private final JdbcTemplate jdbc;

    public EmergencyShippingCsvService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Download download(long orderId) {
        AdminUserDetails actor = WarehouseExportCsvService.requireAdmin();
        // Lock order: Order first (the record and the administrator are locked by V100).
        var locked = jdbc.queryForList("select id from orders where id=? for share", Long.class, orderId);
        if (locked.isEmpty()) throw new WarehouseExportCsvService.NotDownloadableException("注文が見つかりません。");
        var record = jdbc.query("""
                select e.id, e.prepared_at from emergency_shipping_preparations e join orders o on o.id=e.order_id
                where e.order_id=? and o.shipping_preparation_started_at is not distinct from e.prepared_at
                  and o.content_revision = e.content_revision
                """, (r, n) -> new Object[] {r.getLong("id"), r.getObject("prepared_at", LocalDateTime.class)}, orderId);
        if (record.isEmpty()) {
            throw new WarehouseExportCsvService.NotDownloadableException("緊急出荷として発送準備を開始した注文だけ、緊急出荷用CSVをダウンロードできます。");
        }
        long recordId = (Long) record.getFirst()[0];
        LocalDate businessDate = ((LocalDateTime) record.getFirst()[1]).toLocalDate();
        var rows = rows(orderId, "E" + recordId, businessDate);
        if (rows.isEmpty()) throw new WarehouseExportCsvService.NotDownloadableException("注文の明細がありません。");
        byte[] content = WarehouseExportCsvService.render(rows);
        String filename = jdbc.queryForObject("""
                insert into emergency_shipping_csv_downloads(emergency_shipping_preparation_id,order_id,admin_account_id,
                    admin_username,business_date,row_count,content_sha256,filename,downloaded_at)
                values (?,?,?,?,?,?,?,'',localtimestamp)
                returning filename
                """, String.class, recordId, orderId, actor.getId(), actor.getUsername(), businessDate, rows.size(),
                WarehouseExportCsvService.sha256(content));
        return new Download(filename, content, rows.size());
    }

    public List<DownloadRecord> downloads(long orderId) {
        return jdbc.query("""
                select id,admin_username,downloaded_at,row_count,filename from emergency_shipping_csv_downloads
                where order_id=? order by id desc
                """, (r, n) -> new DownloadRecord(r.getLong("id"), r.getString("admin_username"),
                        r.getObject("downloaded_at", LocalDateTime.class), r.getInt("row_count"), r.getString("filename")),
                orderId);
    }

    private List<WarehouseExportCsvService.Row> rows(long orderId, String batchId, LocalDate businessDate) {
        // Same item order as the warehouse export lines (V86: order items by id).
        return jdbc.query("""
                select o.id as order_id, row_number() over (order by i.id)::int as item_no, o.shipping_name,
                  o.shipping_postal_code, o.shipping_prefecture, o.shipping_city, o.shipping_address_line, o.shipping_phone,
                  i.product_id, i.product_name, i.quantity
                from orders o join order_items i on i.order_id=o.id
                where o.id=?
                order by i.id
                """, (r, n) -> new WarehouseExportCsvService.Row(batchId, businessDate, r.getLong("order_id"),
                        r.getInt("item_no"), r.getString("shipping_name"), r.getString("shipping_postal_code"),
                        r.getString("shipping_prefecture"), r.getString("shipping_city"), r.getString("shipping_address_line"),
                        r.getString("shipping_phone"), r.getLong("product_id"), r.getString("product_name"), r.getInt("quantity")),
                orderId);
    }
}
