package com.example.ecsite.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.OrderContentChangeHistory;

public interface OrderContentChangeHistoryRepository
        extends JpaRepository<OrderContentChangeHistory, Long> {

    List<OrderContentChangeHistory> findByOrderIdOrderByChangedAtDescIdDesc(Long orderId);
}
