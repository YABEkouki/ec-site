package com.example.ecsite.service.emergency;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Feature136: emergency shipping records (V97) and the administrator permission flag.
 * The permission flag is granted and revoked by operator SQL only; the application only reads it.
 */
@Component
public class EmergencyShippingStore {
    public static final int MAX_REASON_LENGTH = 500;

    private final JdbcTemplate jdbc;

    public EmergencyShippingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Current value in the database (not the login session): an enabled administrator with the flag. */
    public boolean isAllowed(Long adminAccountId) {
        if (adminAccountId == null || adminAccountId <= 0) return false;
        List<Boolean> found = jdbc.queryForList(
                "select enabled and emergency_shipping_allowed from admin_accounts where id=?", Boolean.class, adminAccountId);
        return found.size() == 1 && Boolean.TRUE.equals(found.getFirst());
    }

    public boolean hasOpenPaymentDiscrepancy(long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from payment_discrepancies d join payments p on p.id=d.payment_id
                              where p.order_id=? and d.status='OPEN')
                """, Boolean.class, orderId));
    }

    /**
     * Inserts the record while the order is locked and still unprepared; V97 checks the permission and the
     * order/payment state again, takes the snapshot from the locked rows and fixes created_at to the DB clock.
     */
    public Record insert(long orderId, long adminAccountId, String adminUsername, String reason, LocalDateTime preparedAt) {
        return jdbc.queryForObject("""
                insert into emergency_shipping_preparations(order_id,admin_account_id,admin_username,reason,prepared_at,
                    order_status,content_revision,total_amount,change_deadline_at,created_at)
                values (?,?,?,?,?,'ORDERED',0,0,localtimestamp,localtimestamp)
                returning id,order_id,admin_account_id,admin_username,reason,prepared_at,order_status,content_revision,
                    total_amount,active_payment_id,change_deadline_at,created_at
                """, (r, n) -> record(r), orderId, adminAccountId, adminUsername, reason, preparedAt);
    }

    public Optional<Record> find(long orderId) {
        return jdbc.query("""
                select id,order_id,admin_account_id,admin_username,reason,prepared_at,order_status,content_revision,
                    total_amount,active_payment_id,change_deadline_at,created_at
                from emergency_shipping_preparations where order_id=?
                """, (r, n) -> record(r), orderId).stream().findFirst();
    }

    private static Record record(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Record(r.getLong("id"), r.getLong("order_id"), r.getLong("admin_account_id"),
                r.getString("admin_username"), r.getString("reason"), r.getObject("prepared_at", LocalDateTime.class),
                r.getString("order_status"), r.getInt("content_revision"), r.getInt("total_amount"),
                (Long) r.getObject("active_payment_id"), r.getObject("change_deadline_at", LocalDateTime.class),
                r.getObject("created_at", LocalDateTime.class));
    }

    public record Record(long id, long orderId, long adminAccountId, String adminUsername, String reason,
            LocalDateTime preparedAt, String orderStatus, int contentRevision, int totalAmount, Long activePaymentId,
            LocalDateTime changeDeadlineAt, LocalDateTime createdAt) {
    }
}
