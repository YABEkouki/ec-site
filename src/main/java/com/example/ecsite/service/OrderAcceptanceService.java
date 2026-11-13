package com.example.ecsite.service;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.OrderRepository;

/**
 * Feature127 formal order acceptance.
 *
 * Formal acceptance is recorded in the same transaction that applies the successful initial
 * authorization (after 3-D Secure, when required). The caller has already locked Order and then
 * its Payments (Order first, Payments by id). The acceptance time is the Asia/Tokyo database clock
 * read after those locks, so every later deadline consumer that locks Order and then reads the
 * database clock observes a time at or after it. No caller-supplied timestamp is accepted.
 * V81 triggers verify the stamp, the 14:00 deadline rule, immutability and the evidence row.
 */
@Service
public class OrderAcceptanceService {

    private final OrderRepository orders;
    private final OrderDeadlineCalculator deadlines;
    private final JdbcTemplate jdbc;

    public OrderAcceptanceService(OrderRepository orders, OrderDeadlineCalculator deadlines, JdbcTemplate jdbc) {
        this.orders = orders;
        this.deadlines = deadlines;
        this.jdbc = jdbc;
    }

    /**
     * Idempotent: returns false for LEGACY or already accepted orders.
     * Must run inside the authorization result transaction holding the Order lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean acceptAfterInitialAuthorization(Order order, long paymentId, long authorizationTransactionId) {
        Objects.requireNonNull(order, "order");
        if (!order.isAwaitingAcceptance()) return false;
        LocalDateTime acceptedAt = orders.findCurrentTimeInTokyo();
        LocalDateTime deadline = deadlines.calculate(acceptedAt);
        order.acceptFormally(acceptedAt, deadline);
        // Flush Order (and the already applied Payment/Transaction success) before the evidence
        // insert, whose trigger cross-checks them. The deferred V81 check requires both at commit.
        orders.flush();
        jdbc.update("""
                insert into order_acceptances(order_id,payment_id,authorization_transaction_id,accepted_at,change_deadline_at)
                select o.id,?,?,o.accepted_at,o.change_deadline_at from orders o where o.id=?
                """, paymentId, authorizationTransactionId, order.getId());
        return true;
    }
}
