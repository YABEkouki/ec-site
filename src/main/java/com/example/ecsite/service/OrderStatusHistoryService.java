package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.repository.OrderStatusHistoryRepository;

@Service
public class OrderStatusHistoryService {

    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    public OrderStatusHistoryService(
            OrderStatusHistoryRepository orderStatusHistoryRepository) {

        this.orderStatusHistoryRepository =
                orderStatusHistoryRepository;
    }

    @Transactional
    public void record(
            Order order,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            OrderStatusHistoryActorType changedByType,
            Long changedByAccountId,
            String changedByUsername) {

        OrderStatusHistory history =
                OrderStatusHistory.create(
                        order,
                        fromStatus,
                        toStatus,
                        changedByType,
                        changedByAccountId,
                        changedByUsername);

        orderStatusHistoryRepository.save(history);
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistory> findByOrderId(Long orderId) {

        return orderStatusHistoryRepository
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }
}
