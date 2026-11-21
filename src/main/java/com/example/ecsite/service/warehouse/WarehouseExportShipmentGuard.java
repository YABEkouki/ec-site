package com.example.ecsite.service.warehouse;

import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.ecsite.entity.Order;

/**
 * Feature131 C-2: an order prepared by the warehouse export ships only against what the warehouse received
 * (design doc ch.8: the exported content and the Capture payment must match). Callers hold the Order lock.
 * V87 enforces the same rule when the order becomes SHIPPED.
 *
 * Feature138-A: an emergency-shipped order (administrator preparation with its V97 record, handed to the warehouse
 * as files since Feature137) ships only against its record in the same way, and may also be shipped by the warehouse
 * shipment import. V103 enforces both rules.
 */
@Component
public class WarehouseExportShipmentGuard {
    private final JdbcTemplate jdbc;

    public WarehouseExportShipmentGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The content and payment the warehouse received for an emergency-shipped order (V97 record). */
    private record EmergencyRecord(long adminAccountId, String adminUsername, java.time.LocalDateTime preparedAt,
            String orderStatus, int contentRevision, int totalAmount, Long activePaymentId) {}

    /**
     * True for a legacy administrator-prepared order without an emergency record (unchanged rules); for an exported
     * order whose line matches {@code paymentId} (null for a paid order shipped without Capture) and the current
     * revision and amount; or for an emergency-shipped order matching its record: same revision and amount, and a
     * capture payment (equal to the recorded active payment when one was recorded) for a record taken while
     * ORDERED, no capture for one taken while PAID.
     */
    public boolean matchesExport(Order order, Long paymentId) {
        if (order == null) return false;
        if (order.isShippingPreparedByWarehouseExport()) {
            var lines = jdbc.query("select payment_id,content_revision,total_amount from warehouse_export_lines where order_id=?",
                    (r, n) -> Objects.equals((Long) r.getObject("payment_id"), paymentId)
                            && r.getInt("content_revision") == order.getContentRevision()
                            && r.getInt("total_amount") == order.getTotalAmount(),
                    order.getId());
            return lines.size() == 1 && lines.getFirst();
        }
        var record = emergencyRecord(order);
        if (record == null) return true;
        boolean payment = "ORDERED".equals(record.orderStatus())
                ? paymentId != null && (record.activePaymentId() == null || record.activePaymentId().equals(paymentId))
                : paymentId == null;
        return payment && record.contentRevision() == order.getContentRevision()
                && record.totalAmount() == order.getTotalAmount();
    }

    /**
     * Whether the warehouse shipment import may ship this order as the system: an exported order with its export
     * line, or an emergency-shipped order whose record describes exactly its preparation.
     */
    public boolean allowsSystemShipment(Order order) {
        if (order == null || !order.isShippingPreparedForSystemShipment()) return false;
        if (order.isShippingPreparedByWarehouseExport()) {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from warehouse_export_lines where order_id=?)", Boolean.class, order.getId()));
        }
        var record = emergencyRecord(order);
        return record != null && record.adminAccountId() == order.getShippingPreparationStartedByAccountId()
                && record.adminUsername().equals(order.getShippingPreparationStartedByUsername())
                && record.preparedAt().equals(order.getShippingPreparationStartedAt());
    }

    private EmergencyRecord emergencyRecord(Order order) {
        if (order.getId() == null || order.getShippingPreparationStartedByAccountId() == null) return null;
        return jdbc.query("""
            select admin_account_id,admin_username,prepared_at,order_status,content_revision,total_amount,active_payment_id
            from emergency_shipping_preparations where order_id=?
            """, (r, n) -> new EmergencyRecord(r.getLong(1), r.getString(2), r.getObject(3, java.time.LocalDateTime.class),
                r.getString(4), r.getInt(5), r.getInt(6), r.getObject(7, Long.class)), order.getId())
                .stream().findFirst().orElse(null);
    }
}
