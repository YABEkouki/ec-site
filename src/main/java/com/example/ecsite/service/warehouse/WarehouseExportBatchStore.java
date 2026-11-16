package com.example.ecsite.service.warehouse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature131 C-1: warehouse export batches and the unlocked candidate scan. Batch times are set by the
 * V86 trigger from the database clock; callers never supply them.
 */
@Component
public class WarehouseExportBatchStore {

    public enum State { OPEN, SEALED }

    /** A batch row. {@code lineCount} is fixed when the batch is sealed. Times are Asia/Tokyo wall time. */
    public record Batch(long id, LocalDate businessDate, State state, LocalDateTime openedAt,
            LocalDateTime sealedAt, Integer lineCount) {
        public boolean open() { return state == State.OPEN; }
    }

    private static final String COLUMNS = "id,business_date,state,opened_at,sealed_at,line_count";

    private final JdbcTemplate jdbc;

    public WarehouseExportBatchStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the REGULAR batch of the business date, opening it if absent. The database refuses to
     * open it before 14:05 of that date. Concurrent callers converge on the same row.
     */
    @Transactional
    public Batch openOrGet(LocalDate businessDate) {
        if (businessDate == null) throw new IllegalArgumentException("業務日が必要です。");
        jdbc.update("""
            insert into warehouse_export_batches(business_date) values (?)
            on conflict on constraint uq_warehouse_export_batch_day do nothing
            """, businessDate);
        return jdbc.queryForObject("select " + COLUMNS + " from warehouse_export_batches where business_date=? and kind='REGULAR'",
                WarehouseExportBatchStore::batch, businessDate);
    }

    public Optional<Batch> find(long batchId) {
        return jdbc.query("select " + COLUMNS + " from warehouse_export_batches where id=?",
                WarehouseExportBatchStore::batch, batchId).stream().findFirst();
    }

    /**
     * Seals an open batch; the trigger counts its lines under the batch row lock, after every line writer
     * holding FOR SHARE has committed. Sealing an already sealed batch returns it unchanged.
     */
    @Transactional
    public Batch seal(long batchId) {
        jdbc.update("update warehouse_export_batches set state='SEALED' where id=? and state='OPEN'", batchId);
        return find(batchId).orElseThrow(() -> new IllegalArgumentException("倉庫出力バッチが存在しません。"));
    }

    /**
     * Unlocked scan of orders that may belong to the business date's export: not yet prepared, ORDERED or PAID,
     * formally accepted, with a change deadline on or before the business date and not before {@code startDate}.
     * Every candidate is re-judged under its Order lock by {@link WarehouseExportOrderTransactions}.
     * Keyset pagination by id ({@code afterId} exclusive), so held orders never make a scan loop forever.
     */
    public List<Long> candidates(LocalDate businessDate, LocalDate startDate, long afterId, int limit) {
        if (businessDate == null || startDate == null || limit <= 0) throw new IllegalArgumentException("Invalid candidate scan");
        return jdbc.queryForList("""
            select id from orders
            where status in ('ORDERED','PAID')
              and shipping_preparation_started_at is null
              and change_deadline_at is not null
              and (acceptance_mode='LEGACY' or accepted_at is not null)
              and change_deadline_at >= ?::date
              and change_deadline_at < ?::date + 1
              and id > ?
            order by id
            limit ?
            """, Long.class, startDate, businessDate, afterId, limit);
    }

    /** Batches newest first, with how often each was downloaded. */
    public List<Listed> list(int limit) {
        return jdbc.query("""
            select b.id,b.business_date,b.state,b.opened_at,b.sealed_at,b.line_count,
              (select count(*) from warehouse_export_lines l where l.batch_id=b.id) as current_lines,
              (select count(*) from warehouse_export_downloads d where d.batch_id=b.id) as downloads,
              (select max(d.downloaded_at) from warehouse_export_downloads d where d.batch_id=b.id) as last_download
            from warehouse_export_batches b order by b.business_date desc, b.id desc limit ?
            """, (r, n) -> new Listed(batch(r, n), r.getInt("current_lines"), r.getInt("downloads"),
                r.getObject("last_download", LocalDateTime.class)), limit);
    }

    public record Listed(Batch batch, int currentLines, int downloads, LocalDateTime lastDownload) {}

    /** An export line as shown to administrators. */
    public record Line(long orderId, String orderStatus, int contentRevision, int totalAmount, Long paymentId,
            LocalDateTime changeDeadlineAt, LocalDateTime preparedAt, String shippingName, int itemCount) {}

    public record DownloadRecord(long id, long adminAccountId, String adminUsername, LocalDateTime downloadedAt,
            int lineCount, int rowCount, String sha256) {}

    public List<Line> lines(long batchId) {
        return jdbc.query("""
            select order_id,order_status,content_revision,total_amount,payment_id,change_deadline_at,prepared_at,
              shipping_name,jsonb_array_length(items) as item_count
            from warehouse_export_lines where batch_id=? order by order_id
            """, (r, n) -> new Line(r.getLong("order_id"), r.getString("order_status"), r.getInt("content_revision"),
                r.getInt("total_amount"), (Long) r.getObject("payment_id"), r.getObject("change_deadline_at", LocalDateTime.class),
                r.getObject("prepared_at", LocalDateTime.class), r.getString("shipping_name"), r.getInt("item_count")), batchId);
    }

    public List<DownloadRecord> downloads(long batchId) {
        return jdbc.query("""
            select id,admin_account_id,admin_username,downloaded_at,line_count,row_count,encode(content_sha256,'hex') as sha256
            from warehouse_export_downloads where batch_id=? order by id desc
            """, (r, n) -> new DownloadRecord(r.getLong("id"), r.getLong("admin_account_id"), r.getString("admin_username"),
                r.getObject("downloaded_at", LocalDateTime.class), r.getInt("line_count"), r.getInt("row_count"),
                r.getString("sha256")), batchId);
    }

    private static Batch batch(ResultSet r, int n) throws SQLException {
        var sealed = r.getObject("sealed_at", LocalDateTime.class);
        return new Batch(r.getLong("id"), r.getObject("business_date", LocalDate.class),
                State.valueOf(r.getString("state")), r.getObject("opened_at", LocalDateTime.class),
                sealed, (Integer) r.getObject("line_count"));
    }
}
