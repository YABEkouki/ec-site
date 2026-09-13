package com.example.ecsite.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.OrderAssigneeHistory;

public interface OrderAssigneeHistoryRepository
        extends JpaRepository<OrderAssigneeHistory, Long> {

    List<OrderAssigneeHistory> findByOrderIdOrderByChangedAtAscIdAsc(
            Long orderId);

    @Query("""
            SELECT h
            FROM OrderAssigneeHistory h
            WHERE (:orderId IS NULL OR h.order.id = :orderId)
              AND (
                    :fromUnassigned IS NULL
                    OR (
                        :fromUnassigned = true
                        AND h.fromAdminAccountId IS NULL
                    )
                    OR (
                        :fromUnassigned = false
                        AND h.fromAdminAccountId = :fromAdminAccountId
                    )
                  )
              AND (
                    :toUnassigned IS NULL
                    OR (
                        :toUnassigned = true
                        AND h.toAdminAccountId IS NULL
                    )
                    OR (
                        :toUnassigned = false
                        AND h.toAdminAccountId = :toAdminAccountId
                    )
                  )
              AND h.changedByUsername LIKE CONCAT(
                    '%',
                    COALESCE(:changedByUsername, ''),
                    '%'
                  )
              AND h.changedAt >= :from
              AND h.changedAt < :toExclusive
            ORDER BY h.changedAt DESC, h.id DESC
            """)
    Page<OrderAssigneeHistory> search(
            @Param("orderId") Long orderId,
            @Param("fromUnassigned") Boolean fromUnassigned,
            @Param("fromAdminAccountId") Long fromAdminAccountId,
            @Param("toUnassigned") Boolean toUnassigned,
            @Param("toAdminAccountId") Long toAdminAccountId,
            @Param("changedByUsername") String changedByUsername,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            Pageable pageable);
}
