package com.example.ecsite.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.example.ecsite.entity.Order;

/**
 * Side-effect-free deadline and preparation checks, not operation permissions.
 * Callers must also enforce ownership, order status and payment conditions.
 *
 * All times are Asia/Tokyo wall-clock values. Mutation callers must lock Order
 * (and any required Payments) before obtaining now from
 * OrderRepository.findCurrentTimeInTokyo(). This policy neither locks nor reads time.
 */
@Component
public class OrderModificationPolicy {

    /** Normal modifications and new cancellations require an open deadline. */
    public boolean allowsModification(Order order, LocalDateTime now) {
        return hasValidDeadline(order, now)
                && isPreparationUnstarted(order)
                && order.isWithinModificationPeriod(now);
    }

    /** Preparation may start at the deadline, but cannot be started again. */
    public boolean allowsPreparationStart(Order order, LocalDateTime now) {
        return hasValidDeadline(order, now)
                && isPreparationUnstarted(order)
                && !order.isWithinModificationPeriod(now);
    }

    private boolean hasValidDeadline(Order order, LocalDateTime now) {
        return order != null
                && now != null
                && order.getOrderedAt() != null
                && order.getChangeDeadlineAt() != null
                && order.getChangeDeadlineAt().isAfter(order.getOrderedAt());
    }

    private boolean isPreparationUnstarted(Order order) {
        // Reject partial metadata too; V53 normally prevents this in persisted rows.
        return order.getShippingPreparationStartedAt() == null
                && order.getShippingPreparationStartedByAccountId() == null
                && order.getShippingPreparationStartedByUsername() == null;
    }
}
