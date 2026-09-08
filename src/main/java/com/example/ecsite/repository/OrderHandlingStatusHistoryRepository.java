package com.example.ecsite.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.OrderHandlingStatusHistory;

public interface OrderHandlingStatusHistoryRepository
        extends JpaRepository<OrderHandlingStatusHistory, Long> {

    List<OrderHandlingStatusHistory> findByOrderIdOrderByChangedAtAscIdAsc(
            Long orderId);
}
