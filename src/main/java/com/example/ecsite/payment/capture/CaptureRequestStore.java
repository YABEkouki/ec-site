package com.example.ecsite.payment.capture;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import com.example.ecsite.config.CaptureConnectionProperties;
import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentTransaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Insert/read-only persistence API, unused by existing Capture execution.
 * Invoke through the Spring bean in an existing transaction. The caller must flush the
 * IDENTITY parent first and acquire any business locks. JdbcTemplate joins the JPA-bound
 * connection; this class never opens a connection, starts a transaction or flushes JPA.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CaptureRequestStore {
    private static final String COLUMNS = """
        id,payment_id,capture_transaction_id,transaction_type,provider,payment_method,provider_flow_id,
        connection_id,provider_account_ref,mode,api_origin,http_method,api_path,content_type,
        amount_to_capture,order_content_revision,idempotency_key,body_format_version,request_body,
        fingerprint_version,request_fingerprint,created_at,replay_not_after
        """;
    private static final String INSERT = """
        insert into capture_requests(payment_id,capture_transaction_id,transaction_type,provider,payment_method,
        provider_flow_id,connection_id,provider_account_ref,mode,api_origin,http_method,api_path,content_type,
        amount_to_capture,order_content_revision,idempotency_key,body_format_version,request_body,
        fingerprint_version,request_fingerprint,created_at,replay_not_after)
        values (?,?,'CAPTURE','PAYJP','CARD',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning
        """ + COLUMNS;
    private final JdbcTemplate jdbc;
    private final CaptureConnectionProperties connection;
    private final PayJpProperties active;

    public CaptureRequestStore(JdbcTemplate jdbc, CaptureConnectionProperties connection, PayJpProperties active) {
        this.jdbc = jdbc; this.connection = connection; this.active = active;
    }
    public StoredCaptureRequest insert(Payment payment, PaymentTransaction capture, CaptureHttpRequest request,
            Instant createdAt, Instant replayNotAfter) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("A writable existing transaction is required");
        }
        var identity = connection.requireIdentity(active);
        StoredCaptureRequest.validateTarget(payment, capture, request, identity);
        // Validate all metadata before issuing INSERT, using the known parent IDs.
        StoredCaptureRequest.validateMetadata(payment.getId(), capture.getId(),
                capture.getOrderContentRevision(), request, createdAt, replayNotAfter);
        return jdbc.queryForObject(INSERT, (rs, n) -> map(rs, identity), payment.getId(), capture.getId(),
                request.flowId(), identity.connectionId(), identity.providerAccountRef(), identity.mode(), identity.apiOrigin(),
                request.method(), request.path(), request.contentType(), request.amount(), capture.getOrderContentRevision(),
                request.idempotencyKey(), request.bodyFormatVersion(), request.body(), request.fingerprintVersion(),
                request.fingerprint(), createdAt.atOffset(ZoneOffset.UTC), replayNotAfter.atOffset(ZoneOffset.UTC));
    }
    public Optional<StoredCaptureRequest> findByCaptureTransactionId(Long transactionId) {
        requirePositive(transactionId);
        var identity = connection.requireIdentity(active);
        return unique(jdbc.query("select " + COLUMNS + " from capture_requests where capture_transaction_id=?",
                (rs, n) -> map(rs, identity), transactionId));
    }
    public Optional<StoredCaptureRequest> findByIdAndCaptureTransactionId(Long requestId, Long transactionId) {
        requirePositive(requestId); requirePositive(transactionId);
        var identity = connection.requireIdentity(active);
        return unique(jdbc.query("select " + COLUMNS + " from capture_requests where id=? and capture_transaction_id=?",
                (rs, n) -> map(rs, identity), requestId, transactionId));
    }
    private static Optional<StoredCaptureRequest> unique(java.util.List<StoredCaptureRequest> rows) {
        if (rows.size() > 1) throw new org.springframework.dao.IncorrectResultSizeDataAccessException(1, rows.size());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
    static StoredCaptureRequest map(ResultSet rs, CaptureConnectionIdentity expected) throws SQLException {
        if (!"CAPTURE".equals(rs.getString("transaction_type")) || !"PAYJP".equals(rs.getString("provider"))
                || !"CARD".equals(rs.getString("payment_method"))) {
            throw new IllegalArgumentException("Invalid persisted Capture request type");
        }
        var identity = new CaptureConnectionIdentity(rs.getString("connection_id"), rs.getString("provider_account_ref"),
                rs.getString("mode"), rs.getString("api_origin"));
        if (!identity.equals(expected)) throw new IllegalArgumentException("Persisted Capture connection mismatch");
        var request = CaptureHttpRequest.restore(identity, rs.getString("provider_flow_id"), integer(rs,"amount_to_capture"),
                rs.getString("idempotency_key"), rs.getString("http_method"), rs.getString("api_path"), rs.getString("content_type"),
                integer(rs,"body_format_version"), rs.getBytes("request_body"), integer(rs,"fingerprint_version"), rs.getBytes("request_fingerprint"));
        var created = rs.getObject("created_at", OffsetDateTime.class);
        var deadline = rs.getObject("replay_not_after", OffsetDateTime.class);
        return new StoredCaptureRequest(number(rs,"id"), number(rs,"payment_id"), number(rs,"capture_transaction_id"),
                integer(rs,"order_content_revision"), request, created == null ? null : created.toInstant(),
                deadline == null ? null : deadline.toInstant());
    }
    private static int integer(ResultSet rs, String column) throws SQLException {
        int value=rs.getInt(column);if(rs.wasNull())throw new IllegalArgumentException("Missing Capture request integer");return value;
    }
    private static long number(ResultSet rs, String column) throws SQLException {
        long value=rs.getLong(column);if(rs.wasNull())throw new IllegalArgumentException("Missing Capture request identifier");return value;
    }
    private static void requirePositive(Long value) {
        if (value == null || value <= 0) throw new IllegalArgumentException("A positive Capture request identifier is required");
    }
}
