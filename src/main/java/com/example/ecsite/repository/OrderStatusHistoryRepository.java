package com.example.ecsite.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.OrderStatusHistory;

public interface OrderStatusHistoryRepository
        extends JpaRepository<OrderStatusHistory, Long> {

    List<OrderStatusHistory>
            findByOrderIdOrderByChangedAtAscIdAsc(Long orderId);
}
