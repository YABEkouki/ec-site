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
     */
    public List<Long> candidates(LocalDate businessDate, LocalDate startDate, int limit) {
        if (businessDate == null || startDate == null || limit <= 0) throw new IllegalArgumentException("Invalid candidate scan");
        return jdbc.queryForList("""
            select id from orders
            where status in ('ORDERED','PAID')
              and shipping_preparation_started_at is null
              and change_deadline_at is not null
              and (acceptance_mode='LEGACY' or accepted_at is not null)
              and change_deadline_at >= ?::date
              and change_deadline_at < ?::date + 1
            order by change_deadline_at, id
            limit ?
            """, Long.class, startDate, businessDate, limit);
    }

    private static Batch batch(ResultSet r, int n) throws SQLException {
        var sealed = r.getObject("sealed_at", LocalDateTime.class);
        return new Batch(r.getLong("id"), r.getObject("business_date", LocalDate.class),
                State.valueOf(r.getString("state")), r.getObject("opened_at", LocalDateTime.class),
                sealed, (Integer) r.getObject("line_count"));
    }
}
