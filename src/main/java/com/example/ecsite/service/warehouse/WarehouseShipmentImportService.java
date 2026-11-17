package com.example.ecsite.service.warehouse;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.security.AdminUserDetails;

/**
 * Feature132-B: accepts a shipped CSV uploaded by an administrator. Every row is judged in the upload transaction
 * and recorded: rejected with a reason, already shipped, or PENDING for the background worker. No order is
 * shipped here. The same file (SHA-256) is accepted only once; uploading it again returns the first import.
 */
@Service
public class WarehouseShipmentImportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");
    private static final Pattern TRACKING = Pattern.compile("[A-Za-z0-9-]{1,50}");
    private static final long UPLOAD_LOCK = 132_002L;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    /** {@code duplicate} is true when the file had already been imported (nothing new was recorded). */
    public record Accepted(long importId, boolean duplicate) {}

    private final JdbcTemplate jdbc;

    public WarehouseShipmentImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Accepted upload(String originalFileName, byte[] content) {
        AdminUserDetails admin = requireAdmin();
        var rows = WarehouseShipmentCsvParser.parse(content);
        byte[] sha256 = sha256(content);
        // Uploads are rare: one at a time, so a file or an order is never claimed by two concurrent uploads.
        jdbc.queryForList("select pg_advisory_xact_lock(?)", UPLOAD_LOCK);
        var existing = findBySha256(sha256);
        if (existing.isPresent()) return new Accepted(existing.get(), true);
        long importId = jdbc.queryForObject("""
            insert into warehouse_shipment_imports(file_name,content_sha256,byte_size,line_count,uploaded_by_account_id,uploaded_by_username)
            values (?,?,?,?,?,?) returning id
            """, Long.class, fileName(originalFileName), sha256, content.length, rows.size(), admin.getId(), admin.getUsername());
        LocalDate today = jdbc.queryForObject("select (timezone('Asia/Tokyo',clock_timestamp()))::date", LocalDate.class);
        var seen = new HashSet<Long>();
        for (var row : rows) record(importId, row, today, seen);
        return new Accepted(importId, false);
    }

    private void record(long importId, WarehouseShipmentCsvParser.Row row, LocalDate today, java.util.Set<Long> seen) {
        List<String> f = row.fields();
        if (f == null || !ID.matcher(f.get(0)).matches() || !ID.matcher(f.get(1)).matches()) {
            reject(importId, row, null, null, WarehouseShipmentImportReason.INVALID_FORMAT);
            return;
        }
        long batchId = Long.parseLong(f.get(0)), orderId = Long.parseLong(f.get(1));
        LocalDate shippedOn;
        try {
            shippedOn = LocalDate.parse(f.get(2), DATE);
        } catch (DateTimeParseException e) {
            shippedOn = null;
        }
        if (shippedOn == null || shippedOn.isAfter(today)) {
            reject(importId, row, batchId, orderId, WarehouseShipmentImportReason.INVALID_DATE);
            return;
        }
        String carrier = f.get(3);
        if (carrier.isBlank() || carrier.length() > 50 || CONTROL.matcher(carrier).find()) {
            reject(importId, row, batchId, orderId, WarehouseShipmentImportReason.INVALID_CARRIER);
            return;
        }
        String tracking = f.get(4);
        if (!TRACKING.matcher(tracking).matches()) {
            reject(importId, row, batchId, orderId, WarehouseShipmentImportReason.INVALID_TRACKING);
            return;
        }
        if (!seen.add(orderId)) {
            reject(importId, row, batchId, orderId, WarehouseShipmentImportReason.DUPLICATE_IN_FILE);
            return;
        }
        var batchState = jdbc.query("""
            select b.state from warehouse_export_lines l join warehouse_export_batches b on b.id=l.batch_id
            where l.batch_id=? and l.order_id=? for share of b
            """, (r, n) -> r.getString(1), batchId, orderId);
        if (batchState.isEmpty()) {
            reject(importId, row, batchId, orderId, WarehouseShipmentImportReason.NOT_EXPORTED);
            return;
        }
        if (!"SEALED".equals(batchState.getFirst())) {
            reject(importId, row, batchId, orderId, WarehouseShipmentImportReason.BATCH_NOT_SEALED);
            return;
        }
        Integer active = jdbc.queryForObject("""
            select count(*) from warehouse_shipment_import_lines
            where order_id=? and state in ('PENDING','SHIPPED','CAPTURE_UNCONFIRMED','CAPTURE_FAILED')
            """, Integer.class, orderId);
        if (active != null && active > 0) {
            reject(importId, row, batchId, orderId, WarehouseShipmentImportReason.ALREADY_IMPORTED);
            return;
        }
        String status = jdbc.queryForObject("select status from orders where id=?", String.class, orderId);
        WarehouseShipmentImportLineState state;
        if ("SHIPPED".equals(status)) state = WarehouseShipmentImportLineState.ALREADY_SHIPPED;
        else if ("ORDERED".equals(status) || "PAID".equals(status)) state = WarehouseShipmentImportLineState.PENDING;
        else {
            reject(importId, row, batchId, orderId, WarehouseShipmentImportReason.ORDER_NOT_SHIPPABLE);
            return;
        }
        jdbc.update("""
            insert into warehouse_shipment_import_lines(import_id,line_no,line_text,batch_id,order_id,csv_shipped_on,carrier,tracking_number,state)
            values (?,?,?,?,?,?,?,?,?)
            """, importId, row.lineNo(), row.lineText(), batchId, orderId, shippedOn, carrier, tracking, state.name());
    }

    private void reject(long importId, WarehouseShipmentCsvParser.Row row, Long batchId, Long orderId,
            WarehouseShipmentImportReason reason) {
        // Only ids that exist are kept as references; the raw text always shows what the warehouse sent.
        Long batch = batchId != null && exists("warehouse_export_batches", batchId) ? batchId : null;
        Long order = orderId != null && exists("orders", orderId) ? orderId : null;
        jdbc.update("""
            insert into warehouse_shipment_import_lines(import_id,line_no,line_text,batch_id,order_id,state,reason)
            values (?,?,?,?,?,'REJECTED',?)
            """, importId, row.lineNo(), row.lineText(), batch, order, reason.name());
    }

    private boolean exists(String table, long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from " + table + " where id=?)", Boolean.class, id));
    }

    private Optional<Long> findBySha256(byte[] sha256) {
        return jdbc.query("select id from warehouse_shipment_imports where content_sha256=?", (r, n) -> r.getLong(1), sha256)
                .stream().findFirst();
    }

    static String fileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = CONTROL.matcher(name).replaceAll("").strip();
        if (name.isEmpty()) name = "shipped.csv";
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static AdminUserDetails requireAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))
                || !(authentication.getPrincipal() instanceof AdminUserDetails admin)
                || admin.getId() == null || admin.getId() <= 0) {
            throw new AccessDeniedException("有効な管理者認証が必要です。");
        }
        return admin;
    }
}
