package com.example.ecsite.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderHandlingStatusHistory;
import com.example.ecsite.repository.projection.OrderHandlingStatusUpdatedAtProjection;

public interface OrderHandlingStatusHistoryRepository
        extends JpaRepository<OrderHandlingStatusHistory, Long> {

    List<OrderHandlingStatusHistory> findByOrderIdOrderByChangedAtAscIdAsc(
            Long orderId);

    @Query("""
            SELECT h.order.id AS orderId,
                   MAX(h.changedAt) AS updatedAt
            FROM OrderHandlingStatusHistory h
            WHERE h.order.id IN :orderIds
            GROUP BY h.order.id
            """)
    List<OrderHandlingStatusUpdatedAtProjection> findLatestUpdatedAtByOrderIds(
            @Param("orderIds") List<Long> orderIds);

    @Query("""
            SELECT h
            FROM OrderHandlingStatusHistory h
            WHERE (:orderId IS NULL OR h.order.id = :orderId)
              AND (:fromStatus IS NULL OR h.fromStatus = :fromStatus)
              AND (:toStatus IS NULL OR h.toStatus = :toStatus)
              AND h.changedByUsername LIKE CONCAT('%', COALESCE(:changedByUsername, ''), '%')
              AND h.changedAt >= :from
              AND h.changedAt < :toExclusive
            ORDER BY h.changedAt DESC, h.id DESC
            """)
    Page<OrderHandlingStatusHistory> search(
            @Param("orderId") Long orderId,
            @Param("fromStatus") OrderHandlingStatus fromStatus,
            @Param("toStatus") OrderHandlingStatus toStatus,
            @Param("changedByUsername") String changedByUsername,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            Pageable pageable);
}
