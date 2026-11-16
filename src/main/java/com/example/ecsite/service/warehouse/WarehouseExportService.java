package com.example.ecsite.service.warehouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.service.warehouse.WarehouseExportHoldException.Reason;
import com.example.ecsite.service.warehouse.WarehouseExportOrderTransactions.Exported;

/**
 * Feature131 C-1: one short transaction per order. A held order leaves no trace in C-1 (hold history is C-3)
 * and is simply judged again by a later run.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class WarehouseExportService {
    private static final Logger log = LoggerFactory.getLogger(WarehouseExportService.class);

    /** Either the stored export line or the hold reason. */
    public record Outcome(Exported exported, Reason held) {
        public boolean isExported() { return exported != null; }
    }

    private final WarehouseExportOrderTransactions transactions;
    private final WarehouseExportHoldStore holds;

    public WarehouseExportService(WarehouseExportOrderTransactions transactions, WarehouseExportHoldStore holds) {
        this.transactions = transactions;
        this.holds = holds;
    }

    /** A hold is recorded (C-3) after the order's transaction has rolled back. */
    public Outcome export(long batchId, long orderId) {
        try {
            return new Outcome(transactions.exportOrder(batchId, orderId), null);
        } catch (WarehouseExportHoldException hold) {
            if (hold.reason() == Reason.DATABASE_GUARD) {
                log.warn("Warehouse export rejected by database guard. batchId={}, orderId={}", batchId, orderId);
            }
            holds.recordQuietly(batchId, orderId, hold.reason());
            return new Outcome(null, hold.reason());
        }
    }
}
