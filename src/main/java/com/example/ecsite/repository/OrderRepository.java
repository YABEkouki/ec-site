package com.example.ecsite.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.Order;

public interface OrderRepository
                extends JpaRepository<Order, Long> {

        @EntityGraph(attributePaths = "items")
        List<Order> findByUserIdOrderByOrderedAtDesc(
                        Long userId);
}