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

    public LocalDateTime changeGraceDeadline(Order order) {
        return order.getChangeDeadlineAt().plusMinutes(10);
    }

    /** Decision is irreversible; crossing the grace boundary cannot abort a decided switch. */
    public boolean canExpireChange(LocalDateTime graceUntil,LocalDateTime switchDecidedAt,LocalDateTime now) {
        return switchDecidedAt==null && graceUntil!=null && now!=null && !now.isBefore(graceUntil);
    }

    /**
     * Unaccepted checkout orders (3-D Secure or authorization still pending) have no deadline
     * and are never modifiable nor shipping-preparation candidates. Accepted orders are judged
     * against the deadline fixed from the formal acceptance time; LEGACY ones from ordered_at.
     */
    private boolean hasValidDeadline(Order order, LocalDateTime now) {
        if (order == null || now == null || order.getOrderedAt() == null
                || order.getChangeDeadlineAt() == null || !order.isFormallyAccepted()) {
            return false;
        }
        LocalDateTime basis = order.getAcceptedAt() != null ? order.getAcceptedAt() : order.getOrderedAt();
        return order.getChangeDeadlineAt().isAfter(basis);
    }

    private boolean isPreparationUnstarted(Order order) {
        // Reject partial metadata too; V53 normally prevents this in persisted rows.
        return order.getShippingPreparationStartedAt() == null
                && order.getShippingPreparationStartedByAccountId() == null
                && order.getShippingPreparationStartedByUsername() == null;
    }
}
