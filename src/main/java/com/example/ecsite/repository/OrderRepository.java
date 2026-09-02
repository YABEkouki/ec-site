package com.example.ecsite.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.repository.projection.DailySalesProjection;
import com.example.ecsite.repository.projection.ProductSalesRankingProjection;

import jakarta.persistence.LockModeType;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT o
            FROM Order o
            WHERE o.id = :id
            """)
    Optional<Order> findByIdForUpdate(
            @Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT o
            FROM Order o
            WHERE o.id = :id
              AND o.userId = :userId
            """)
    Optional<Order> findByIdAndUserIdForUpdate(
            @Param("id") Long id,
            @Param("userId") Long userId);

    long countByStatus(OrderStatus status);

    @Query("""
            SELECT o
            FROM Order o
            WHERE (:orderId IS NULL OR o.id = :orderId)
              AND (:userId IS NULL OR o.userId = :userId)
              AND o.orderedAt >= :from
              AND o.orderedAt < :toExclusive
              AND (:status IS NULL OR o.status = :status)
            ORDER BY o.orderedAt DESC, o.id DESC
            """)
    Page<Order> search(
            @Param("orderId") Long orderId,
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            @Param("status") OrderStatus status,
            Pageable pageable);

    @Query("""
            SELECT COUNT(o)
            FROM Order o
            WHERE o.orderedAt >= :from
              AND o.orderedAt < :toExclusive
              AND o.status IN (
                    com.example.ecsite.entity.OrderStatus.PAID,
                    com.example.ecsite.entity.OrderStatus.SHIPPED
              )
            """)
    long countSalesOrders(
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive);

    @Query("""
            SELECT COALESCE(SUM(o.totalAmount), 0)
            FROM Order o
            WHERE o.orderedAt >= :from
              AND o.orderedAt < :toExclusive
              AND o.status IN (
                    com.example.ecsite.entity.OrderStatus.PAID,
                    com.example.ecsite.entity.OrderStatus.SHIPPED
              )
            """)
    long sumSalesAmount(
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive);

    @Query("""
            SELECT COUNT(o)
            FROM Order o
            WHERE o.status = :status
              AND o.orderedAt >= :from
              AND o.orderedAt < :toExclusive
            """)
    long countByStatusAndOrderedAtRange(
            @Param("status") OrderStatus status,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive);

    @Query(value = """
            SELECT
                    CAST(o.ordered_at AS DATE) AS date,
                    COUNT(*) AS orderCount,
                    COUNT(*) FILTER (
                            WHERE o.status IN ('PAID', 'SHIPPED')
                    ) AS salesOrderCount,
                    COALESCE(SUM(o.total_amount) FILTER (
                            WHERE o.status IN ('PAID', 'SHIPPED')
                    ), 0) AS salesAmount
            FROM orders o
            WHERE o.ordered_at >= :from
              AND o.ordered_at < :toExclusive
            GROUP BY CAST(o.ordered_at AS DATE)
            ORDER BY CAST(o.ordered_at AS DATE)
            """, nativeQuery = true)
    List<DailySalesProjection> findDailySales(
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive);

    @Query("""
            SELECT
                i.productId AS productId,
                i.productName AS productName,
                SUM(i.quantity) AS quantity,
                COUNT(DISTINCT o.id) AS orderCount,
                SUM(i.subtotal) AS salesAmount
            FROM Order o
            JOIN o.items i
            WHERE o.status IN (
                com.example.ecsite.entity.OrderStatus.PAID,
                com.example.ecsite.entity.OrderStatus.SHIPPED
            )
              AND o.orderedAt >= :from
              AND o.orderedAt < :toExclusive
            GROUP BY i.productId, i.productName
            ORDER BY SUM(i.subtotal) DESC
            """)
    List<ProductSalesRankingProjection> findProductSalesRanking(
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            Pageable pageable);

}
