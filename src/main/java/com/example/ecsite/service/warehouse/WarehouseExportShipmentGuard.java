package com.example.ecsite.service.warehouse;

import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.ecsite.entity.Order;

/**
 * Feature131 C-2: an order prepared by the warehouse export ships only against what the warehouse received
 * (design doc ch.8: the exported content and the Capture payment must match). Callers hold the Order lock.
 * V87 enforces the same rule when the order becomes SHIPPED.
 */
@Component
public class WarehouseExportShipmentGuard {
    private final JdbcTemplate jdbc;

    public WarehouseExportShipmentGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True for an administrator-prepared order (unchanged rules), or for an exported order whose line matches
     * {@code paymentId} (null for a paid order shipped without Capture) and the current revision and amount.
     */
    public boolean matchesExport(Order order, Long paymentId) {
        if (order == null) return false;
        if (!order.isShippingPreparedByWarehouseExport()) return true;
        var lines = jdbc.query("select payment_id,content_revision,total_amount from warehouse_export_lines where order_id=?",
                (r, n) -> Objects.equals((Long) r.getObject("payment_id"), paymentId)
                        && r.getInt("content_revision") == order.getContentRevision()
                        && r.getInt("total_amount") == order.getTotalAmount(),
                order.getId());
        return lines.size() == 1 && lines.getFirst();
    }
}
