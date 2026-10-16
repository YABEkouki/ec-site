package com.example.ecsite.service;

import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import org.springframework.stereotype.Component;

/** Preparation metadata only; callers separately enforce status, payment and locking rules. */
@Component
public class OrderShipmentGuard {
    public void requirePrepared(Order order) {
        if (order == null) throw new IllegalArgumentException("注文は必須です。");
        if (order.hasCompleteShippingPreparation()) return;
        if (order.getShippingPreparationStartedAt() == null
                && order.getShippingPreparationStartedByAccountId() == null
                && order.getShippingPreparationStartedByUsername() == null) {
            throw new InvalidOrderStatusException("発送準備を開始してから発送してください。");
        }
        throw new InvalidOrderStatusException("発送準備情報に不整合があるため、発送できません。");
    }
}
