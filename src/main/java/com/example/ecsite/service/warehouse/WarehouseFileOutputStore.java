package com.example.ecsite.service.warehouse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.security.AdminUserDetails;

/**
 * Feature137-B: the warehouse file outputs (V102). Rows are created by the database when a batch is sealed or an
 * emergency shipping is recorded; this store claims them for writing, records the outcome and the administrator
 * re-output. Every method is one short transaction; the files are written between them with nothing locked.
 */
@Component
public class WarehouseFileOutputStore {

    public enum Kind { REGULAR, EMERGENCY }

    public enum State {
        PENDING("出力待ち"), WRITTEN("出力済み"), FAILED("出力失敗");
        private final String label;
        State(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** What a writer needs; {@code headerRows}/{@code detailRows} are the counts fixed by V102. */
    public record Claimed(long id, Kind kind, Long batchId, Long emergencyRecordId, String headerFilename,
            String detailFilename, int headerRows, int detailRows, int attempts) {}

    /** An output as shown to administrators. Times are Asia/Tokyo wall time. */
    public record Output(long id, Kind kind, Long batchId, Long orderId, String outputBatchId, LocalDate businessDate,
            String headerFilename, String detailFilename, int headerRows, int detailRows, State state, int attempts,
            LocalDateTime nextAttemptAt, LocalDateTime lastAttemptedAt, String lastError, String headerSha256,
            String detailSha256, LocalDateTime writtenAt, LocalDateTime createdAt) {
        public boolean failed() { return state == State.FAILED; }
    }

    /** Feature137-C dashboard: failed outputs by kind, and outputs still pending after {@code staleAfter}. */
    public record Summary(long failedRegular, long failedEmergency, long stalePending) {
        public long failed() { return failedRegular + failedEmergency; }
    }

    public record Retry(long id, String adminUsername, LocalDateTime requestedAt, int previousAttempts, String previousError) {}

    public static class NotRetryableException extends RuntimeException {
        public NotRetryableException(String message) { super(message); }
    }

    static final int MAX_ERROR_LENGTH = 500;
    private static final String NOW = "timezone('Asia/Tokyo',clock_timestamp())";
    private static final String COLUMNS = """
            id,kind,batch_id,order_id,output_batch_id,business_date,header_filename,detail_filename,header_row_count,
            detail_row_count,state,attempts,next_attempt_at,last_attempted_at,last_error,encode(header_sha256,'hex') as header_hex,
            encode(detail_sha256,'hex') as detail_hex,written_at,created_at
            """;

    private final JdbcTemplate jdbc;

    public WarehouseFileOutputStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims the oldest due output whose lease is free, or nothing. */
    @Transactional
    public Optional<Claimed> claim(UUID owner, Duration lease) {
        return jdbc.query("""
                update warehouse_file_outputs set claimed_by=?, claimed_until=%1$s + make_interval(secs => ?)
                where id = (select id from warehouse_file_outputs
                            where state='PENDING' and next_attempt_at <= %1$s
                              and (claimed_until is null or claimed_until < %1$s)
                            order by next_attempt_at, id limit 1 for update skip locked)
                returning id,kind,batch_id,emergency_shipping_preparation_id,header_filename,detail_filename,
                  header_row_count,detail_row_count,attempts
                """.formatted(NOW), (r, n) -> new Claimed(r.getLong("id"), Kind.valueOf(r.getString("kind")),
                        (Long) r.getObject("batch_id", Long.class), (Long) r.getObject("emergency_shipping_preparation_id", Long.class),
                        r.getString("header_filename"), r.getString("detail_filename"), r.getInt("header_row_count"),
                        r.getInt("detail_row_count"), r.getInt("attempts")),
                owner, (double) lease.toSeconds()).stream().findFirst();
    }

    /** Records the written files. False when the claim was lost (another writer took the expired lease). */
    @Transactional
    public boolean markWritten(long id, UUID owner, byte[] headerSha256, byte[] detailSha256) {
        return jdbc.update("""
                update warehouse_file_outputs set state='WRITTEN', attempts=attempts+1, header_sha256=?, detail_sha256=?
                where id=? and state='PENDING' and claimed_by=?
                """, headerSha256, detailSha256, id, owner) == 1;
    }

    /** Records a failed attempt: retried after {@code retryDelay}, or FAILED once {@code maxAttempts} are used. */
    @Transactional
    public boolean markFailed(long id, UUID owner, String error, int maxAttempts, Duration retryDelay) {
        String message = error == null || error.isBlank() ? "不明なエラー" : error.strip();
        if (message.length() > MAX_ERROR_LENGTH) message = message.substring(0, MAX_ERROR_LENGTH);
        return jdbc.update("""
                update warehouse_file_outputs set attempts=attempts+1, last_error=?, claimed_by=null, claimed_until=null,
                  state=case when attempts+1 >= ? then 'FAILED' else 'PENDING' end,
                  next_attempt_at=%s + make_interval(secs => ?)
                where id=? and state='PENDING' and claimed_by=?
                """.formatted(NOW), message, maxAttempts, (double) retryDelay.toSeconds(), id, owner) == 1;
    }

    public Optional<Output> find(long id) {
        return jdbc.query("select " + COLUMNS + " from warehouse_file_outputs where id=?", WarehouseFileOutputStore::output, id)
                .stream().findFirst();
    }

    public Optional<Output> forBatch(long batchId) {
        return jdbc.query("select " + COLUMNS + " from warehouse_file_outputs where batch_id=?", WarehouseFileOutputStore::output, batchId)
                .stream().findFirst();
    }

    public Optional<Output> forOrder(long orderId) {
        return jdbc.query("select " + COLUMNS + " from warehouse_file_outputs where order_id=? order by id desc limit 1",
                WarehouseFileOutputStore::output, orderId).stream().findFirst();
    }

    public Summary summary(Duration staleAfter) {
        return jdbc.queryForObject("""
                select count(*) filter (where state='FAILED' and kind='REGULAR'),
                  count(*) filter (where state='FAILED' and kind='EMERGENCY'),
                  count(*) filter (where state='PENDING' and created_at < %s - make_interval(secs => ?))
                from warehouse_file_outputs
                """.formatted(NOW), (r, n) -> new Summary(r.getLong(1), r.getLong(2), r.getLong(3)),
                (double) staleAfter.toSeconds());
    }

    public List<Retry> retries(long outputId) {
        return jdbc.query("""
                select id,admin_username,requested_at,previous_attempts,previous_error from warehouse_file_output_retries
                where output_id=? order by id desc
                """, (r, n) -> new Retry(r.getLong("id"), r.getString("admin_username"),
                        r.getObject("requested_at", LocalDateTime.class), r.getInt("previous_attempts"), r.getString("previous_error")),
                outputId);
    }

    /**
     * Administrator re-output of a FAILED output (decision 11): the retry row is recorded with the administrator and
     * V102 puts the output back to PENDING. Every administrator may do this.
     */
    @Transactional
    public Output retry(long outputId) {
        AdminUserDetails actor = WarehouseExportCsvService.requireAdmin();
        var locked = jdbc.queryForList("select state from warehouse_file_outputs where id=? for update", String.class, outputId);
        if (locked.isEmpty()) throw new NotRetryableException("倉庫ファイルの出力記録が見つかりません。");
        if (!State.FAILED.name().equals(locked.getFirst())) {
            throw new NotRetryableException("出力に失敗した記録だけ再出力できます。");
        }
        jdbc.update("""
                insert into warehouse_file_output_retries(output_id,admin_account_id,admin_username,previous_attempts,requested_at)
                values (?,?,?,0,localtimestamp)
                """, outputId, actor.getId(), actor.getUsername());
        return find(outputId).orElseThrow();
    }

    private static Output output(ResultSet r, int n) throws SQLException {
        return new Output(r.getLong("id"), Kind.valueOf(r.getString("kind")), (Long) r.getObject("batch_id", Long.class),
                (Long) r.getObject("order_id", Long.class), r.getString("output_batch_id"),
                r.getObject("business_date", LocalDate.class), r.getString("header_filename"), r.getString("detail_filename"),
                r.getInt("header_row_count"), r.getInt("detail_row_count"), State.valueOf(r.getString("state")),
                r.getInt("attempts"), r.getObject("next_attempt_at", LocalDateTime.class),
                r.getObject("last_attempted_at", LocalDateTime.class), r.getString("last_error"), r.getString("header_hex"),
                r.getString("detail_hex"), r.getObject("written_at", LocalDateTime.class),
                r.getObject("created_at", LocalDateTime.class));
    }
}
