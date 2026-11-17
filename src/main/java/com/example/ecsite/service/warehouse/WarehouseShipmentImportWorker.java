package com.example.ecsite.service.warehouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ecsite.service.ManualOrderShipmentService;
import com.example.ecsite.service.payment.PaymentCaptureService;
import com.example.ecsite.service.warehouse.WarehouseShipmentImportLineStore.OrderState;

/**
 * Feature132-B: ships the orders of PENDING import lines as the system, one line at a time, outside any
 * transaction (card orders call PAY.JP). The outcome is always read back from the database rather than inferred
 * from the call: an order that ended up SHIPPED by the import is the line's shipment, whatever the call returned.
 * A refused or interrupted line is retried by later passes up to {@code max-attempts}, then rejected.
 * An unknown capture result is never guessed: the line waits as CAPTURE_UNCONFIRMED until reconciliation settles
 * the capture.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class WarehouseShipmentImportWorker {
    private static final Logger log = LoggerFactory.getLogger(WarehouseShipmentImportWorker.class);

    public record Result(int processed, int settled) {}

    private final WarehouseShipmentImportLineStore lines;
    private final PaymentCaptureService captures;
    private final ManualOrderShipmentService manualShipments;
    private final TransactionTemplate transaction;
    private final int maxAttempts;
    private final int pageSize;

    public WarehouseShipmentImportWorker(WarehouseShipmentImportLineStore lines, PaymentCaptureService captures,
            ManualOrderShipmentService manualShipments, PlatformTransactionManager manager,
            @Value("${app.warehouse-shipment-import.max-attempts:3}") int maxAttempts,
            @Value("${app.warehouse-shipment-import.page-size:50}") int pageSize) {
        if (maxAttempts <= 0 || pageSize <= 0)
            throw new IllegalArgumentException("app.warehouse-shipment-import.max-attempts and page-size must be positive");
        this.lines = lines;
        this.captures = captures;
        this.manualShipments = manualShipments;
        this.transaction = new TransactionTemplate(manager);
        this.maxAttempts = maxAttempts;
        this.pageSize = pageSize;
    }

    /** One pass: settles waiting captures, then works through up to page-size pending lines. */
    public Result runPass() {
        int settled = 0;
        for (var line : lines.unconfirmed(pageSize)) {
            var outcome = shippedOutcome(lines.orderState(line.orderId()));
            if (outcome != null && outcome != WarehouseShipmentImportLineState.CAPTURE_UNCONFIRMED
                    && lines.record(line.lineId(), WarehouseShipmentImportLineState.CAPTURE_UNCONFIRMED, outcome, null)) settled++;
        }
        int processed = 0;
        long after = 0;
        while (processed < pageSize) {
            var claimed = lines.claimNext(after);
            if (claimed.isEmpty()) break;
            after = claimed.get().lineId();
            process(claimed.get());
            processed++;
        }
        return new Result(processed, settled);
    }

    private void process(WarehouseShipmentImportLineStore.Claimed line) {
        var before = lines.orderState(line.orderId());
        RuntimeException failure = null;
        if (!before.status().equals("SHIPPED")) {
            if (line.attempts() > maxAttempts) {
                finish(line, WarehouseShipmentImportLineState.REJECTED, WarehouseShipmentImportReason.RETRY_LIMIT);
                return;
            }
            try {
                switch (before.status()) {
                    case "ORDERED" -> captures.captureForWarehouseImport(line.orderId(), WarehouseShipmentActor.INSTANCE);
                    case "PAID" -> transaction.executeWithoutResult(s ->
                            manualShipments.shipAsWarehouseImport(line.orderId(), WarehouseShipmentActor.INSTANCE));
                    default -> {
                        finish(line, WarehouseShipmentImportLineState.REJECTED, WarehouseShipmentImportReason.ORDER_NOT_SHIPPABLE);
                        return;
                    }
                }
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        var after = lines.orderState(line.orderId());
        var shipped = shippedOutcome(after);
        if (shipped != null) {
            if (failure != null) log.warn("Warehouse shipment import line {} shipped order {} with an unsettled capture",
                    line.lineId(), line.orderId(), failure);
            finish(line, shipped, null);
        } else if (after.status().equals("SHIPPED")) {
            finish(line, WarehouseShipmentImportLineState.ALREADY_SHIPPED, null);
        } else if (failure != null) {
            log.warn("Warehouse shipment import line {} could not ship order {} (attempt {})",
                    line.lineId(), line.orderId(), line.attempts(), failure);
            if (line.attempts() >= maxAttempts)
                finish(line, WarehouseShipmentImportLineState.REJECTED, WarehouseShipmentImportReason.SHIPMENT_REFUSED);
        } else if (!after.status().equals("ORDERED") && !after.status().equals("PAID")) {
            finish(line, WarehouseShipmentImportLineState.REJECTED, WarehouseShipmentImportReason.ORDER_NOT_SHIPPABLE);
        }
        // Otherwise the line stays PENDING and a later pass tries again.
    }

    private void finish(WarehouseShipmentImportLineStore.Claimed line, WarehouseShipmentImportLineState to,
            WarehouseShipmentImportReason reason) {
        lines.record(line.lineId(), WarehouseShipmentImportLineState.PENDING, to, reason);
    }

    /** The line outcome of an order shipped by the import, or null when the import did not ship it. */
    static WarehouseShipmentImportLineState shippedOutcome(OrderState o) {
        if (!"SHIPPED".equals(o.status()) || !o.shippedByImport()) return null;
        if (o.capturePaymentId() == null) return WarehouseShipmentImportLineState.SHIPPED;
        if ("SUCCEEDED".equals(o.captureStatus())) return WarehouseShipmentImportLineState.SHIPPED;
        if ("FAILED".equals(o.captureStatus())) return WarehouseShipmentImportLineState.CAPTURE_FAILED;
        return WarehouseShipmentImportLineState.CAPTURE_UNCONFIRMED;
    }
}
