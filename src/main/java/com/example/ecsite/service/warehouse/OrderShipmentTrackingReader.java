package com.example.ecsite.service.warehouse;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature132-C: the carrier, tracking number and warehouse ship date of an order shipped by the warehouse shipment
 * import. They come from the one import line that shipped the order (V90 allows a single active line per order,
 * and its content is immutable), and only while the order is SHIPPED by the import.
 */
@Service
@Transactional(readOnly = true)
public class OrderShipmentTrackingReader {

    /** {@code lineState} is the import line state, shown to administrators only. */
    public record Tracking(String carrier, String trackingNumber, LocalDate csvShippedOn, long importId,
            WarehouseShipmentImportLineState lineState) {}

    private final JdbcTemplate jdbc;

    public OrderShipmentTrackingReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tracking> find(Long orderId) {
        if (orderId == null) return Optional.empty();
        return jdbc.query("""
            select l.carrier,l.tracking_number,l.csv_shipped_on,l.import_id,l.state
            from warehouse_shipment_import_lines l join orders o on o.id=l.order_id
            where l.order_id=? and l.state in ('SHIPPED','CAPTURE_UNCONFIRMED','CAPTURE_FAILED')
              and o.status='SHIPPED' and o.shipped_by_account_id is null and o.shipped_by_username='WAREHOUSE_SHIPMENT_IMPORT'
            """, (r, n) -> new Tracking(r.getString(1), r.getString(2), r.getObject(3, LocalDate.class), r.getLong(4),
                WarehouseShipmentImportLineState.valueOf(r.getString(5))), orderId).stream().findFirst();
    }
}
