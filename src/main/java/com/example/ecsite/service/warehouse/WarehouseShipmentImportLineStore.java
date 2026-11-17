package com.example.ecsite.service.warehouse;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature132-B: short transactions of the background worker. Each method commits on its own, so no lock is held
 * while an order is shipped or PAY.JP is called. Every outcome is written only from the state it was read in.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class WarehouseShipmentImportLineStore {

    public record Claimed(long lineId, long orderId, int attempts) {}

    /** What the order looks like now, as far as the import outcome is concerned. */
    public record OrderState(String status, boolean shippedByImport, Long capturePaymentId, String captureStatus) {}

    private final JdbcTemplate jdbc;

    public WarehouseShipmentImportLineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes the next PENDING line after {@code afterId} that no other worker holds, counting one more attempt. */
    public Optional<Claimed> claimNext(long afterId) {
        var ids = jdbc.query("""
            select id from warehouse_shipment_import_lines where state='PENDING' and id>? order by id limit 1 for update skip locked
            """, (r, n) -> r.getLong(1), afterId);
        if (ids.isEmpty()) return Optional.empty();
        return Optional.of(jdbc.queryForObject("""
            update warehouse_shipment_import_lines set attempts=attempts+1 where id=? returning id,order_id,attempts
            """, (r, n) -> new Claimed(r.getLong(1), r.getLong(2), r.getInt(3)), ids.getFirst()));
    }

    /** CAPTURE_UNCONFIRMED lines, oldest first. */
    public List<Claimed> unconfirmed(int limit) {
        return jdbc.query("""
            select id,order_id,attempts from warehouse_shipment_import_lines where state='CAPTURE_UNCONFIRMED' order by id limit ?
            """, (r, n) -> new Claimed(r.getLong(1), r.getLong(2), r.getInt(3)), limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public OrderState orderState(long orderId) {
        return jdbc.queryForObject("""
            select o.status, o.shipped_by_account_id is null and o.shipped_by_username='WAREHOUSE_SHIPMENT_IMPORT' as by_import,
              o.shipment_capture_payment_id,
              (select t.status from payment_transactions t where t.payment_id=o.shipment_capture_payment_id
                 and t.transaction_type='CAPTURE' order by t.id limit 1) as capture_status
            from orders o where o.id=?
            """, (r, n) -> new OrderState(r.getString(1), r.getBoolean(2), (Long) r.getObject(3, Long.class), r.getString(4)),
            orderId);
    }

    /** Records an outcome if the line is still in {@code from}; returns false when someone else already did. */
    public boolean record(long lineId, WarehouseShipmentImportLineState from, WarehouseShipmentImportLineState to,
            WarehouseShipmentImportReason reason) {
        var current = jdbc.query("select state from warehouse_shipment_import_lines where id=? for update",
                (r, n) -> r.getString(1), lineId);
        if (current.isEmpty() || !from.name().equals(current.getFirst())) return false;
        if (from == to) return true;
        jdbc.update("update warehouse_shipment_import_lines set state=?, reason=? where id=?",
                to.name(), reason == null ? null : reason.name(), lineId);
        return true;
    }
}
