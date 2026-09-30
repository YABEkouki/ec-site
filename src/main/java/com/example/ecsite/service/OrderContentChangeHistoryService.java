package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.OrderContentChangeHistory;
import com.example.ecsite.repository.OrderContentChangeHistoryRepository;

@Service
public class OrderContentChangeHistoryService {

    private final OrderContentChangeHistoryRepository orderContentChangeHistoryRepository;

    public OrderContentChangeHistoryService(
            OrderContentChangeHistoryRepository orderContentChangeHistoryRepository) {
        this.orderContentChangeHistoryRepository = orderContentChangeHistoryRepository;
    }

    @Transactional(readOnly = true)
    public List<OrderContentChangeHistory> findByOrderId(Long orderId) {
        List<OrderContentChangeHistory> histories =
                orderContentChangeHistoryRepository
                        .findByOrderIdOrderByChangedAtDescIdDesc(orderId);

        histories.forEach(history -> {
            history.getItems().size();
            history.getCharges().size();
        });

        return histories;
    }
}
