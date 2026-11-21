package com.example.ecsite.service.warehouse;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.EntityManager;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderModificationPolicy;
import com.example.ecsite.service.OrderWriteContext;
import com.example.ecsite.service.payment.OrderPaymentGuard;
import com.example.ecsite.service.payment.PaymentService;
import com.example.ecsite.service.warehouse.WarehouseExportHoldException.Reason;

/**
 * Feature131 C-1: exports one order into a warehouse export batch and starts its shipping preparation in the
 * same commit (design doc ch.4.2). Uses exactly the checks of the manual preparation start (ch.8 conditions)
 * plus the 14:05 final decision time. Lock order: Order -> Payments (id asc) -> batch row (FOR SHARE, in V86).
 * A hold throws {@link WarehouseExportHoldException}; the transaction then rolls back and writes nothing.
 */
@Service
public class WarehouseExportOrderTransactions {

    public enum Status { EXPORTED, ALREADY_EXPORTED }

    /** The export line as stored. Times are Asia/Tokyo wall time. */
    public record Exported(Status status, long lineId, long batchId, long orderId, int contentRevision,
            int totalAmount, Long paymentId, LocalDateTime preparedAt) {}

    private final OrderRepository orders;
    private final PaymentService payments;
    private final OrderPaymentGuard paymentGuard;
    private final OrderModificationPolicy policy;
    private final OrderWriteContext writeContext;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public WarehouseExportOrderTransactions(OrderRepository orders, PaymentService payments,
            OrderPaymentGuard paymentGuard, OrderModificationPolicy policy, OrderWriteContext writeContext,
            EntityManager entityManager, JdbcTemplate jdbc) {
        this.orders = orders;
        this.payments = payments;
        this.paymentGuard = paymentGuard;
        this.policy = policy;
        this.writeContext = writeContext;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Transactional
    public Exported exportOrder(long batchId, long orderId) {
        if (batchId <= 0 || orderId <= 0) throw new IllegalArgumentException("正のIDが必要です。");
        writeContext.requireCleanWritableReadCommitted();
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        writeContext.refreshWritableOrder(order);
        if (order.isShippingPreparedByWarehouseExport()) {
            return existing(order);
        }
        if (order.getShippingPreparationStartedAt() != null || order.getShippingPreparationStartedByAccountId() != null
                || order.getShippingPreparationStartedByUsername() != null) {
            throw hold(Reason.MANUALLY_PREPARED, null);
        }
        if (order.getStatus() != OrderStatus.ORDERED && order.getStatus() != OrderStatus.PAID) {
            throw hold(Reason.NOT_EXPORTABLE_STATUS, null);
        }
        try {
            paymentGuard.requireNoOrderChange(order);
        } catch (RuntimeException unresolved) {
            throw hold(Reason.ORDER_CHANGE_UNRESOLVED, unresolved);
        }
        writeContext.refreshPayments(order);
        try {
            if (order.getStatus() == OrderStatus.ORDERED) payments.validateCardPaymentForShippingPreparation(order);
            else paymentGuard.validateManualShipment(order);
        } catch (com.example.ecsite.service.payment.AuthorizationExpiryException expiring) {
            throw hold(Reason.AUTHORIZATION_EXPIRING, expiring);
        } catch (RuntimeException unsafe) {
            throw hold(Reason.PAYMENT_NOT_READY, unsafe);
        }
        var batch = jdbc.queryForList("select business_date from warehouse_export_batches where id=? and state='OPEN'",
                java.time.LocalDate.class, batchId);
        if (batch.size() != 1) throw new IllegalStateException("開いている倉庫出力バッチが必要です。");
        // Feature137: the warehouse file rows (V101) must add up to the order total and tax.
        if (!fileLinesMatch(order)) throw hold(Reason.AMOUNT_MISMATCH, null);
        // Read after all lock waits and payment validation, before any mutation.
        LocalDateTime now = orders.findCurrentTimeInTokyo();
        var deadline = order.getChangeDeadlineAt();
        if (!policy.allowsPreparationStart(order, now) || deadline == null
                || now.isBefore(deadline.plusMinutes(5)) || deadline.toLocalDate().isAfter(batch.getFirst())) {
            throw hold(Reason.NOT_DUE, null);
        }
        order.beginShippingPreparationByWarehouseExport(now);
        entityManager.flush();
        Exported line;
        try {
            // V86 takes the snapshot from the locked rows and re-checks the ch.8 conditions.
            line = jdbc.queryForObject("""
                insert into warehouse_export_lines(batch_id,order_id) values (?,?)
                returning id,batch_id,order_id,content_revision,total_amount,payment_id,prepared_at
                """, (r, n) -> exported(Status.EXPORTED, r), batchId, orderId);
        } catch (org.springframework.dao.DataAccessException rejected) {
            throw hold(Reason.DATABASE_GUARD, rejected);
        }
        if (line == null || !now.equals(line.preparedAt()) || line.contentRevision() != order.getContentRevision()
                || line.totalAmount() != order.getTotalAmount()) {
            throw new IllegalStateException("倉庫出力の記録が注文と一致しません。");
        }
        return line;
    }

    /** Same sums as the V101 snapshot trigger, checked before the order is prepared. */
    private boolean fileLinesMatch(Order order) {
        var sums = jdbc.queryForMap("""
            select count(*) as row_count,
              count(*) filter (where subtotal is null or tax_amount is null or unit_price is null or quantity is null
                                 or tax_rate is null or product_name is null) as incomplete,
              coalesce(sum(subtotal),0) as total, coalesce(sum(tax_amount),0) as tax
            from f137_order_file_lines(?)
            """, order.getId());
        return ((Number) sums.get("row_count")).longValue() > 0 && ((Number) sums.get("incomplete")).longValue() == 0
                && ((Number) sums.get("total")).longValue() == order.getTotalAmount()
                && ((Number) sums.get("tax")).longValue() == order.getTaxAmount();
    }

    private Exported existing(Order order) {
        var found = jdbc.query("""
            select id,batch_id,order_id,content_revision,total_amount,payment_id,prepared_at
            from warehouse_export_lines where order_id=?
            """, (r, n) -> exported(Status.ALREADY_EXPORTED, r), order.getId());
        if (found.size() != 1 || !Objects.equals(found.getFirst().preparedAt(), order.getShippingPreparationStartedAt())) {
            throw new IllegalStateException("自動発送準備に対応する倉庫出力記録がありません。");
        }
        return found.getFirst();
    }

    private static Exported exported(Status status, java.sql.ResultSet r) throws java.sql.SQLException {
        return new Exported(status, r.getLong("id"), r.getLong("batch_id"), r.getLong("order_id"),
                r.getInt("content_revision"), r.getInt("total_amount"), (Long) r.getObject("payment_id"),
                r.getObject("prepared_at", LocalDateTime.class));
    }

    private static WarehouseExportHoldException hold(Reason reason, Throwable cause) {
        return new WarehouseExportHoldException(reason, cause);
    }
}
