package com.example.ecsite.service.emergency;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Feature136-C3: read models of the emergency shipping list/audit screen and the dashboard count. Read only.
 * The operation date is the date of the record's created_at (DB clock, V97). "Unshipped" means neither shipped
 * nor cancelled. Feature137-C: the per-order CSV download was replaced by the warehouse files written when the
 * emergency shipping is started (V102); the list shows that output, and past CSV downloads only as a count.
 */
@Component
public class AdminEmergencyShippingQueries {
    public static final int PAGE_SIZE = 50;
    public static final int PERMISSION_HISTORY_LIMIT = 100;

    public enum Shipment {
        ALL("すべて"), UNSHIPPED("未出荷"), SHIPPED("出荷済み");
        private final String label;
        Shipment(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    /** Feature137-C: the warehouse file output of the emergency shipping (V102). NONE = recorded before V102. */
    public enum FileOutput {
        ALL("すべて"), PENDING("出力待ち"), FAILED("出力失敗"), WRITTEN("出力済み"), NONE("対象外（導入前）");
        private final String label;
        FileOutput(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    /**
     * {@code fileState} is null for an emergency shipping recorded before V102; {@code downloadCount} is V100 history.
     * {@code shippedByAccountId} / {@code shippedByUsername} are the shipper (Feature138-B: the import ships as the system).
     */
    public record Row(long orderId, EmergencyShippingMode mode, LocalDateTime operatedAt, String adminUsername, String reason,
            String orderStatus, LocalDateTime shippedAt, Long shippedByAccountId, String shippedByUsername,
            String fileState, LocalDateTime fileWrittenAt, int downloadCount, String shippingStop) {
        public Row(long orderId, EmergencyShippingMode mode, LocalDateTime operatedAt, String adminUsername, String reason,
                String orderStatus, LocalDateTime shippedAt, Long shippedByAccountId, String shippedByUsername,
                String fileState, LocalDateTime fileWrittenAt, int downloadCount) {
            this(orderId, mode, operatedAt, adminUsername, reason, orderStatus, shippedAt, shippedByAccountId, shippedByUsername,
                    fileState, fileWrittenAt, downloadCount, null);
        }
        /** Feature146: 「出荷中止（取消済み）」or「出荷中止（取消の結果を確認中）」; null without a shipping stop. */
        public String getShippingStopLabel() {
            if ("COMPLETED".equals(shippingStop)) return "出荷中止（取消済み）";
            if ("REQUESTED".equals(shippingStop)) return "出荷中止（取消の結果を確認中）";
            return null;
        }
        public boolean isShipped() { return "SHIPPED".equals(orderStatus); }
        /** 「倉庫出荷取込み」for a shipment by the warehouse shipment import, otherwise the administrator's name. */
        public String getShipperLabel() {
            if (com.example.ecsite.entity.Order.isWarehouseShipmentImportActor(shippedByAccountId, shippedByUsername))
                return "倉庫出荷取込み";
            return shippedByUsername == null ? "" : shippedByUsername;
        }
        public boolean isUnshipped() { return !"SHIPPED".equals(orderStatus) && !"CANCELLED".equals(orderStatus); }
        public String getFileLabel() {
            return fileState == null ? FileOutput.NONE.getLabel() : FileOutput.valueOf(fileState).getLabel();
        }
        public boolean isFileFailed() { return "FAILED".equals(fileState); }
    }

    public record Result(List<Row> rows, int page, long total, boolean hasNext) {}

    public record Summary(long unshipped) {}

    public record PermittedAdmin(long id, String username, boolean enabled) {}

    public record PermissionHistory(long id, String adminUsername, boolean allowed, LocalDateTime changedAt, String changedByDbUser) {}

    private static final String UNSHIPPED = "o.status not in ('SHIPPED','CANCELLED')";
    private static final String FILE_JOIN =
            " left join warehouse_file_outputs f on f.emergency_shipping_preparation_id=e.id";

    private final JdbcTemplate jdbc;

    public AdminEmergencyShippingQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LocalDate today() {
        return jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())::date", LocalDate.class);
    }

    public Result list(LocalDate from, LocalDate to, Shipment shipment, FileOutput file, int page) {
        int safePage = Math.max(page, 0);
        var where = new StringBuilder(" where true");
        var args = new ArrayList<Object>();
        if (from != null) { where.append(" and e.created_at >= ?"); args.add(from.atStartOfDay()); }
        if (to != null) { where.append(" and e.created_at < ?"); args.add(to.plusDays(1).atStartOfDay()); }
        if (shipment == Shipment.UNSHIPPED) where.append(" and ").append(UNSHIPPED);
        if (shipment == Shipment.SHIPPED) where.append(" and o.status = 'SHIPPED'");
        if (file == FileOutput.NONE) where.append(" and f.id is null");
        else if (file != null && file != FileOutput.ALL) { where.append(" and f.state = ?"); args.add(file.name()); }
        String base = " from emergency_shipping_preparations e join orders o on o.id=e.order_id" + FILE_JOIN + where;
        long total = jdbc.queryForObject("select count(*)" + base, Long.class, args.toArray());
        var pageArgs = new ArrayList<Object>(args);
        pageArgs.add(PAGE_SIZE);
        pageArgs.add((long) safePage * PAGE_SIZE);
        List<Row> rows = jdbc.query("""
                select e.order_id,e.mode,e.created_at,e.admin_username,e.reason,o.status,o.shipped_at,
                  o.shipped_by_account_id,o.shipped_by_username,
                  f.state as file_state,f.written_at as file_written_at,
                  (select count(*) from emergency_shipping_csv_downloads d where d.order_id=e.order_id)::int as download_count,
                  (select s.status from order_shipping_stops s where s.order_id=e.order_id and s.status<>'REJECTED'
                   order by s.id desc limit 1) as shipping_stop
                """ + base + " order by e.id desc limit ? offset ?",
                (r, n) -> new Row(r.getLong("order_id"), EmergencyShippingMode.valueOf(r.getString("mode")),
                        r.getObject("created_at", LocalDateTime.class), r.getString("admin_username"), r.getString("reason"),
                        r.getString("status"), r.getObject("shipped_at", LocalDateTime.class),
                        r.getObject("shipped_by_account_id", Long.class), r.getString("shipped_by_username"), r.getString("file_state"),
                        r.getObject("file_written_at", LocalDateTime.class), r.getInt("download_count"), r.getString("shipping_stop")),
                pageArgs.toArray());
        return new Result(rows, safePage, total, (long) (safePage + 1) * PAGE_SIZE < total);
    }

    public Summary summary() {
        return jdbc.queryForObject("select count(*) filter (where " + UNSHIPPED + ")"
                + " from emergency_shipping_preparations e join orders o on o.id=e.order_id",
                (r, n) -> new Summary(r.getLong(1)));
    }

    public List<PermittedAdmin> permittedAdmins() {
        return jdbc.query("select id,username,enabled from admin_accounts where emergency_shipping_allowed order by id",
                (r, n) -> new PermittedAdmin(r.getLong("id"), r.getString("username"), r.getBoolean("enabled")));
    }

    public List<PermissionHistory> permissionHistories() {
        return jdbc.query("""
                select h.id,a.username,h.allowed,h.changed_at,h.changed_by_db_user
                from admin_emergency_shipping_permission_histories h join admin_accounts a on a.id=h.admin_account_id
                order by h.id desc limit ?
                """, (r, n) -> new PermissionHistory(r.getLong("id"), r.getString("username"), r.getBoolean("allowed"),
                        r.getObject("changed_at", LocalDateTime.class), r.getString("changed_by_db_user")),
                PERMISSION_HISTORY_LIMIT);
    }
}
