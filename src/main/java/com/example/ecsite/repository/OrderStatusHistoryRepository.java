package com.example.ecsite.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.OrderStatusHistoryActorType;

public interface OrderStatusHistoryRepository
        extends JpaRepository<OrderStatusHistory, Long> {

    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAscIdAsc(Long orderId);

    @Query("""
            SELECT h
            FROM OrderStatusHistory h
            WHERE (:orderId IS NULL OR h.order.id = :orderId)
              AND (:fromStatus IS NULL OR h.fromStatus = :fromStatus)
              AND (:toStatus IS NULL OR h.toStatus = :toStatus)
              AND (:changedByType IS NULL OR h.changedByType = :changedByType)
              AND h.changedByUsername LIKE CONCAT('%', COALESCE(:changedByUsername, ''), '%')
              AND h.changedAt >= :from
              AND h.changedAt < :toExclusive
            ORDER BY h.changedAt DESC, h.id DESC
            """)
    Page<OrderStatusHistory> search(
            @Param("orderId") Long orderId,
            @Param("fromStatus") OrderStatus fromStatus,
            @Param("toStatus") OrderStatus toStatus,
            @Param("changedByType") OrderStatusHistoryActorType changedByType,
            @Param("changedByUsername") String changedByUsername,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            Pageable pageable);

}
