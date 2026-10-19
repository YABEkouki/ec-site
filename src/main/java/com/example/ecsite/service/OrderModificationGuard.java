package com.example.ecsite.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.repository.OrderRepository;

/** Mutation callers must already hold Order and any required Payment locks. */
@Service
public class OrderModificationGuard {
    private final OrderRepository orders;
    private final OrderModificationPolicy policy;

    public OrderModificationGuard(OrderRepository orders, OrderModificationPolicy policy) {
        this.orders = orders;
        this.policy = policy;
    }

    /** Uses the DB's current Tokyo wall clock, after any lock waits have ended. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDateTime requireAllowed(Order order) {
        LocalDateTime now = orders.findCurrentTimeInTokyo();
        if (!policy.allowsModification(order, now)) {
            throw new InvalidOrderStatusException("この注文の変更受付は終了しています。");
        }
        return now;
    }
}
