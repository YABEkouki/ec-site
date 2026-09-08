package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderHandlingStatusHistory;
import com.example.ecsite.repository.OrderHandlingStatusHistoryRepository;

@Service
public class OrderHandlingStatusHistoryService {

    private final OrderHandlingStatusHistoryRepository repository;

    public OrderHandlingStatusHistoryService(
            OrderHandlingStatusHistoryRepository repository) {
        this.repository = repository;
    }

    public void record(
            Order order,
            OrderHandlingStatus fromStatus,
            OrderHandlingStatus toStatus,
            Long changedByAccountId,
            String changedByUsername) {

        OrderHandlingStatusHistory history =
                OrderHandlingStatusHistory.create(
                        order,
                        fromStatus,
                        toStatus,
                        changedByAccountId,
                        changedByUsername);

        repository.save(history);
    }

    public List<OrderHandlingStatusHistory> findByOrderId(Long orderId) {
        return repository.findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }
}
