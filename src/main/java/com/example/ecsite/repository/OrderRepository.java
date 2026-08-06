package com.example.ecsite.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;

public interface OrderRepository
        extends JpaRepository<Order, Long> {

    Page<Order> findByUserIdOrderByOrderedAtDesc(
            Long userId,
            Pageable pageable);

    Page<Order> findAllByOrderByOrderedAtDesc(
            Pageable pageable);

    Page<Order> findByStatusOrderByOrderedAtDesc(
            OrderStatus status,
            Pageable pageable);

    @Query("""
            SELECT DISTINCT o
            FROM Order o
            LEFT JOIN FETCH o.items
            WHERE o.id = :id
            """)

    Optional<Order> findByIdWithItems(
            @Param("id") Long id);

    @Query("""
            SELECT DISTINCT o
            FROM Order o
            LEFT JOIN FETCH o.items
            WHERE o.id = :id
              AND o.userId = :userId
            """)
    Optional<Order> findByIdAndUserIdWithItems(
            @Param("id") Long id,
            @Param("userId") Long userId);

}