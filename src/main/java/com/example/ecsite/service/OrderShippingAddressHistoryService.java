package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderShippingAddressHistory;
import com.example.ecsite.entity.OrderShippingAddressHistoryActorType;
import com.example.ecsite.repository.OrderShippingAddressHistoryRepository;

@Service
public class OrderShippingAddressHistoryService {

    private final OrderShippingAddressHistoryRepository
            orderShippingAddressHistoryRepository;

    public OrderShippingAddressHistoryService(
            OrderShippingAddressHistoryRepository
                    orderShippingAddressHistoryRepository) {
        this.orderShippingAddressHistoryRepository =
                orderShippingAddressHistoryRepository;
    }

    public void record(
            Order order,
            OrderShippingAddressHistoryActorType changedByType,
            Long changedByAccountId,
            String changedByUsername,
            String oldShippingName,
            String oldShippingPostalCode,
            String oldShippingPrefecture,
            String oldShippingCity,
            String oldShippingAddressLine,
            String oldShippingPhone,
            String newShippingName,
            String newShippingPostalCode,
            String newShippingPrefecture,
            String newShippingCity,
            String newShippingAddressLine,
            String newShippingPhone) {

        OrderShippingAddressHistory history =
                OrderShippingAddressHistory.create(
                        order,
                        changedByType,
                        changedByAccountId,
                        changedByUsername,
                        oldShippingName,
                        oldShippingPostalCode,
                        oldShippingPrefecture,
                        oldShippingCity,
                        oldShippingAddressLine,
                        oldShippingPhone,
                        newShippingName,
                        newShippingPostalCode,
                        newShippingPrefecture,
                        newShippingCity,
                        newShippingAddressLine,
                        newShippingPhone);

        orderShippingAddressHistoryRepository.save(history);
    }

    public List<OrderShippingAddressHistory> findByOrderId(Long orderId) {
        return orderShippingAddressHistoryRepository
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }
}
