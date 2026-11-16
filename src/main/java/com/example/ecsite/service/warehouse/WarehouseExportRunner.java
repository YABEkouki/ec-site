package com.example.ecsite.service.warehouse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.orderchange.OrderChangeFinalDecisionService;

/**
 * Feature131 C-2: the daily 14:05 warehouse export (design doc ch.4.2).
 *
 * The due business date is today from 14:05 (database clock, Asia/Tokyo), otherwise yesterday, so a run missed
 * while the application was down is caught up later. One pass judges every candidate once (one transaction per
 * order), then seals the batch. A held order therefore waits for the next day's batch; it is never added to a
 * batch later the same day. A pass interrupted before sealing is resumed by the next run.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class WarehouseExportRunner {
    private static final Logger log = LoggerFactory.getLogger(WarehouseExportRunner.class);
    static final LocalTime EXPORT_TIME = LocalTime.of(14, 5);

    public enum Status { NOT_STARTED, ALREADY_SEALED, SEALED }

    public record Result(Status status, LocalDate businessDate, Long batchId, int exported, int held, int failed,
            Integer lineCount) {}

    private final WarehouseExportBatchStore batches;
    private final WarehouseExportService exports;
    private final OrderChangeFinalDecisionService finalDecisions;
    private final OrderRepository orders;
    private final LocalDate startDate;
    private final int pageSize;
    private final WarehouseExportHoldStore holds;

    public WarehouseExportRunner(WarehouseExportBatchStore batches, WarehouseExportService exports,
            WarehouseExportHoldStore holds, OrderChangeFinalDecisionService finalDecisions, OrderRepository orders,
            @Value("${app.warehouse-export.start-date:}") String startDate,
            @Value("${app.warehouse-export.page-size:100}") int pageSize) {
        if (pageSize <= 0) throw new IllegalArgumentException("app.warehouse-export.page-size must be positive");
        this.batches = batches;
        this.exports = exports;
        this.holds = holds;
        this.finalDecisions = finalDecisions;
        this.orders = orders;
        this.startDate = startDate == null || startDate.isBlank() ? null : LocalDate.parse(startDate.trim());
        this.pageSize = pageSize;
    }

    /** Null when not configured; the scheduler refuses to start without it. */
    public LocalDate startDate() {
        return startDate;
    }

    static LocalDate dueBusinessDate(LocalDateTime now) {
        return now.toLocalTime().isBefore(EXPORT_TIME) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    public Result runDue() {
        return run(dueBusinessDate(orders.findCurrentTimeInTokyo()));
    }

    /** Runs the export of {@code businessDate}, which must already be due. */
    public Result run(LocalDate businessDate) {
        if (businessDate == null) throw new IllegalArgumentException("業務日が必要です。");
        if (businessDate.isAfter(dueBusinessDate(orders.findCurrentTimeInTokyo()))) {
            throw new IllegalArgumentException("まだ出力時刻（14:05）になっていない業務日です。");
        }
        if (startDate == null || businessDate.isBefore(startDate)) {
            return new Result(Status.NOT_STARTED, businessDate, null, 0, 0, 0, null);
        }
        var existing = batches.openOrGet(businessDate);
        if (!existing.open()) {
            return new Result(Status.ALREADY_SEALED, businessDate, existing.id(), 0, 0, 0, existing.lineCount());
        }
        // Record the 14:05 final decisions first so that a NOT_ADOPTED change returns its order to "original".
        try {
            finalDecisions.finalizeDue(1000);
        } catch (RuntimeException deferred) {
            log.warn("Order change final decisions deferred before warehouse export. businessDate={}", businessDate);
        }
        int exported = 0, held = 0, failed = 0;
        long after = 0;
        while (true) {
            var page = batches.candidates(businessDate, startDate, after, pageSize);
            if (page.isEmpty()) break;
            for (long orderId : page) {
                after = orderId;
                try {
                    if (exports.export(existing.id(), orderId).isExported()) exported++;
                    else held++;
                } catch (RuntimeException failure) {
                    // Treated like a hold: the order stays unprepared and is judged again by the next batch.
                    failed++;
                    log.warn("Warehouse export of an order failed. batchId={}, orderId={}", existing.id(), orderId, failure);
                    if (holds != null) holds.recordQuietly(existing.id(), orderId, WarehouseExportHoldException.Reason.FAILED);
                }
            }
        }
        var sealed = batches.seal(existing.id());
        log.info("Warehouse export sealed. businessDate={}, batchId={}, lines={}, exported={}, held={}, failed={}",
                businessDate, sealed.id(), sealed.lineCount(), exported, held, failed);
        return new Result(Status.SEALED, businessDate, sealed.id(), exported, held, failed, sealed.lineCount());
    }
}
