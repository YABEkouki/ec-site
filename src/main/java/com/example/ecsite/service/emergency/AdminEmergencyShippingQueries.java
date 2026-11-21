package com.example.ecsite.service.emergency;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Feature136-C3: read models of the emergency shipping list/audit screen and the dashboard counts. Read only.
 * The operation date is the date of the record's created_at (DB clock, V97). "Unshipped" means neither shipped
 * nor cancelled; the CSV-not-downloaded count is limited to unshipped orders, since a shipped order needs no
 * further contact with the warehouse.
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

    public enum Csv {
        ALL("すべて"), NOT_DOWNLOADED("未ダウンロード");
        private final String label;
        Csv(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public record Row(long orderId, EmergencyShippingMode mode, LocalDateTime operatedAt, String adminUsername, String reason,
            String orderStatus, LocalDateTime shippedAt, int downloadCount, LocalDateTime lastDownloadedAt) {
        public boolean isShipped() { return "SHIPPED".equals(orderStatus); }
        public boolean isUnshipped() { return !"SHIPPED".equals(orderStatus) && !"CANCELLED".equals(orderStatus); }
    }

    public record Result(List<Row> rows, int page, long total, boolean hasNext) {}

    public record Summary(long unshipped, long unshippedWithoutCsv) {}

    public record PermittedAdmin(long id, String username, boolean enabled) {}

    public record PermissionHistory(long id, String adminUsername, boolean allowed, LocalDateTime changedAt, String changedByDbUser) {}

    private static final String UNSHIPPED = "o.status not in ('SHIPPED','CANCELLED')";
    private static final String NOT_DOWNLOADED =
            "not exists(select 1 from emergency_shipping_csv_downloads d where d.order_id=e.order_id)";

    private final JdbcTemplate jdbc;

    public AdminEmergencyShippingQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LocalDate today() {
        return jdbc.queryForObject("select timezone('Asia/Tokyo',clock_timestamp())::date", LocalDate.class);
    }

    public Result list(LocalDate from, LocalDate to, Shipment shipment, Csv csv, int page) {
        int safePage = Math.max(page, 0);
        var where = new StringBuilder(" where true");
        var args = new ArrayList<Object>();
        if (from != null) { where.append(" and e.created_at >= ?"); args.add(from.atStartOfDay()); }
        if (to != null) { where.append(" and e.created_at < ?"); args.add(to.plusDays(1).atStartOfDay()); }
        if (shipment == Shipment.UNSHIPPED) where.append(" and ").append(UNSHIPPED);
        if (shipment == Shipment.SHIPPED) where.append(" and o.status = 'SHIPPED'");
        if (csv == Csv.NOT_DOWNLOADED) where.append(" and ").append(NOT_DOWNLOADED);
        String base = " from emergency_shipping_preparations e join orders o on o.id=e.order_id" + where;
        long total = jdbc.queryForObject("select count(*)" + base, Long.class, args.toArray());
        var pageArgs = new ArrayList<Object>(args);
        pageArgs.add(PAGE_SIZE);
        pageArgs.add((long) safePage * PAGE_SIZE);
        List<Row> rows = jdbc.query("""
                select e.order_id,e.mode,e.created_at,e.admin_username,e.reason,o.status,o.shipped_at,
                  (select count(*) from emergency_shipping_csv_downloads d where d.order_id=e.order_id)::int as download_count,
                  (select max(d.downloaded_at) from emergency_shipping_csv_downloads d where d.order_id=e.order_id) as last_downloaded_at
                """ + base + " order by e.id desc limit ? offset ?",
                (r, n) -> new Row(r.getLong("order_id"), EmergencyShippingMode.valueOf(r.getString("mode")),
                        r.getObject("created_at", LocalDateTime.class), r.getString("admin_username"), r.getString("reason"),
                        r.getString("status"), r.getObject("shipped_at", LocalDateTime.class), r.getInt("download_count"),
                        r.getObject("last_downloaded_at", LocalDateTime.class)),
                pageArgs.toArray());
        return new Result(rows, safePage, total, (long) (safePage + 1) * PAGE_SIZE < total);
    }

    public Summary summary() {
        return jdbc.queryForObject("select count(*) filter (where " + UNSHIPPED + "),"
                + " count(*) filter (where " + UNSHIPPED + " and " + NOT_DOWNLOADED + ")"
                + " from emergency_shipping_preparations e join orders o on o.id=e.order_id",
                (r, n) -> new Summary(r.getLong(1), r.getLong(2)));
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
