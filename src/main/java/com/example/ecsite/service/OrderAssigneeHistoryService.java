package com.example.ecsite.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderAssigneeHistory;
import com.example.ecsite.repository.OrderAssigneeHistoryRepository;

@Service
public class OrderAssigneeHistoryService {

    private final OrderAssigneeHistoryRepository repository;

    public OrderAssigneeHistoryService(
            OrderAssigneeHistoryRepository repository) {
        this.repository = repository;
    }

    public void record(
            Order order,
            Long fromAdminAccountId,
            String fromAdminUsername,
            Long toAdminAccountId,
            String toAdminUsername,
            Long changedByAccountId,
            String changedByUsername,
            UUID changeEventId) {

        OrderAssigneeHistory history = OrderAssigneeHistory.create(
                order,
                fromAdminAccountId,
                fromAdminUsername,
                toAdminAccountId,
                toAdminUsername,
                changedByAccountId,
                changedByUsername,
                changeEventId);

        repository.save(history);
    }

    public List<OrderAssigneeHistory> findByOrderId(Long orderId) {
        return repository.findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }
}
