package com.example.ecsite.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.OrderAssigneeHistory;

public interface OrderAssigneeHistoryRepository
        extends JpaRepository<OrderAssigneeHistory, Long> {

    List<OrderAssigneeHistory> findByOrderIdOrderByChangedAtAscIdAsc(
            Long orderId);
}
