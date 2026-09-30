package com.example.ecsite.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.OrderContentChangeHistory;

public interface OrderContentChangeHistoryRepository
        extends JpaRepository<OrderContentChangeHistory, Long> {
}
