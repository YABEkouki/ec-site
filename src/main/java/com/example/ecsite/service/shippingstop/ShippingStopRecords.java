package com.example.ecsite.service.shippingstop;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature146-B: the REQUESTED shipping stop of a pending cancellation. Every route that applies an administrator's
 * cancellation result (the stop itself, the recheck, webhooks and reconciliation) completes the stop here, in the
 * transaction that cancels the order: V115 lets a prepared order become CANCELLED only in that transaction.
 */
@Component
public class ShippingStopRecords {
    private final JdbcTemplate jdbc;

    public ShippingStopRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The reason of the REQUESTED stop of this cancellation, locked; empty when the cancellation has none. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> requestedReason(long transactionId) {
        // Tests pinned to a schema before V114 run the same cancellation code without the table.
        if (jdbc.queryForObject("select to_regclass('order_shipping_stops') is null", Boolean.class)) return Optional.empty();
        List<String> found = jdbc.queryForList(
                "select reason from order_shipping_stops where transaction_id = ? and status = 'REQUESTED' for update",
                String.class, transactionId);
        return found.stream().findFirst();
    }

    /** Marks the REQUESTED stop COMPLETED; the caller then cancels the order in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(long transactionId) {
        int updated = jdbc.update(
                "update order_shipping_stops set status = 'COMPLETED' where transaction_id = ? and status = 'REQUESTED'",
                transactionId);
        if (updated != 1) throw new IllegalStateException("出荷中止の記録を完了にできませんでした。");
    }
}
