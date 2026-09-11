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
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.repository.projection.ActionRequiredAgingSummaryProjection;
import com.example.ecsite.repository.projection.AdminActionRequiredOrderSearchProjection;
import com.example.ecsite.repository.projection.CategorySalesRankingProjection;
import com.example.ecsite.repository.projection.CustomerSalesRankingProjection;
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
            LEFT JOIN FETCH o.assignedAdminAccount
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

    long countByHandlingStatus(OrderHandlingStatus handlingStatus);

    @Query(value = """
            SELECT o
            FROM Order o
            LEFT JOIN FETCH o.assignedAdminAccount
            WHERE (:orderId IS NULL OR o.id = :orderId)
              AND (:userId IS NULL OR o.userId = :userId)
              AND o.orderedAt >= :from
              AND o.orderedAt < :toExclusive
              AND (:status IS NULL OR o.status = :status)
              AND o.handlingStatus IN :handlingStatuses
              AND (
                    :assigneeFilter = 'ALL'
                    OR (
                        :assigneeFilter = 'UNASSIGNED'
                        AND o.assignedAdminAccount IS NULL
                    )
                    OR (
                        :assigneeFilter IN ('ME', 'SPECIFIC')
                        AND o.assignedAdminAccount.id = :assignedAdminAccountId
                    )
                  )
            ORDER BY o.orderedAt DESC, o.id DESC
            """, countQuery = """
            SELECT COUNT(o)
            FROM Order o
            WHERE (:orderId IS NULL OR o.id = :orderId)
              AND (:userId IS NULL OR o.userId = :userId)
              AND o.orderedAt >= :from
              AND o.orderedAt < :toExclusive
              AND (:status IS NULL OR o.status = :status)
              AND o.handlingStatus IN :handlingStatuses
              AND (
                    :assigneeFilter = 'ALL'
                    OR (
                        :assigneeFilter = 'UNASSIGNED'
                        AND o.assignedAdminAccount IS NULL
                    )
                    OR (
                        :assigneeFilter IN ('ME', 'SPECIFIC')
                        AND o.assignedAdminAccount.id = :assignedAdminAccountId
                    )
                  )
            """)
    Page<Order> search(
            @Param("orderId") Long orderId,
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            @Param("status") OrderStatus status,
            @Param("handlingStatuses") List<OrderHandlingStatus> handlingStatuses,
            @Param("assigneeFilter") String assigneeFilter,
            @Param("assignedAdminAccountId") Long assignedAdminAccountId,
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

    @Query("""
            SELECT
                u.id AS userId,
                u.username AS username,
                COUNT(DISTINCT o.id) AS orderCount,
                SUM(i.quantity) AS quantity,
                SUM(i.subtotal) AS salesAmount
            FROM User u, Order o
            JOIN o.items i
            WHERE u.id = o.userId
              AND o.status IN (
                com.example.ecsite.entity.OrderStatus.PAID,
                com.example.ecsite.entity.OrderStatus.SHIPPED
            )
              AND o.orderedAt >= :from
              AND o.orderedAt < :toExclusive
            GROUP BY u.id, u.username
            ORDER BY SUM(i.subtotal) DESC, u.id ASC
            """)
    List<CustomerSalesRankingProjection> findCustomerSalesRanking(
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            Pageable pageable);

    @Query("""
            SELECT
                i.categoryId AS categoryId,
                i.categoryName AS categoryName,
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
            GROUP BY i.categoryId, i.categoryName
            ORDER BY SUM(i.subtotal) DESC, i.categoryId ASC
            """)
    List<CategorySalesRankingProjection> findCategorySalesRanking(
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            Pageable pageable);

    @Query(value = """
            SELECT
                o.id AS "orderId",
                MAX(h.changed_at) AS "handlingStatusUpdatedAt"
            FROM orders o
            LEFT JOIN order_handling_status_histories h
                ON h.order_id = o.id
            WHERE (:orderId IS NULL OR o.id = :orderId)
              AND (:userId IS NULL OR o.user_id = :userId)
              AND o.ordered_at >= :from
              AND o.ordered_at < :toExclusive
              AND (:status IS NULL OR o.status = CAST(:status AS VARCHAR))
              AND o.handling_status IN (:handlingStatuses)
              AND (
                    :assigneeFilter = 'ALL'
                    OR (
                        :assigneeFilter = 'UNASSIGNED'
                        AND o.assigned_admin_account_id IS NULL
                    )
                    OR (
                        :assigneeFilter IN ('ME', 'SPECIFIC')
                        AND o.assigned_admin_account_id = :assignedAdminAccountId
                    )
              )
            GROUP BY o.id
            HAVING (
                CAST(:elapsedCutoffExclusive AS timestamp) IS NULL
                OR MAX(h.changed_at) < CAST(:elapsedCutoffExclusive AS timestamp)
            )
            ORDER BY
                CASE
                    WHEN :sort = 'OLDEST'
                         AND MAX(h.changed_at) IS NULL THEN 1
                    ELSE 0
                END ASC,
                CASE
                    WHEN :sort = 'OLDEST' THEN MAX(h.changed_at)
                END ASC,
                CASE
                    WHEN :sort = 'NEWEST'
                         AND MAX(h.changed_at) IS NULL THEN 1
                    ELSE 0
                END ASC,
                CASE
                    WHEN :sort = 'NEWEST' THEN MAX(h.changed_at)
                END DESC,
                o.id DESC
            """, countQuery = """
            SELECT COUNT(*)
            FROM (
                SELECT o.id
                FROM orders o
                LEFT JOIN order_handling_status_histories h
                    ON h.order_id = o.id
                WHERE (:orderId IS NULL OR o.id = :orderId)
                  AND (:userId IS NULL OR o.user_id = :userId)
                  AND o.ordered_at >= :from
                  AND o.ordered_at < :toExclusive
                  AND (:status IS NULL OR o.status = CAST(:status AS VARCHAR))
                  AND o.handling_status IN (:handlingStatuses)
                  AND (
                        :assigneeFilter = 'ALL'
                        OR (
                            :assigneeFilter = 'UNASSIGNED'
                            AND o.assigned_admin_account_id IS NULL
                        )
                        OR (
                            :assigneeFilter IN ('ME', 'SPECIFIC')
                            AND o.assigned_admin_account_id = :assignedAdminAccountId
                        )
                  )
                GROUP BY o.id
                HAVING (
                    CAST(:elapsedCutoffExclusive AS timestamp) IS NULL
                    OR MAX(h.changed_at) < CAST(:elapsedCutoffExclusive AS timestamp)
                )
            ) filtered_orders
            """, nativeQuery = true)
    Page<AdminActionRequiredOrderSearchProjection> searchActionRequiredOrders(
            @Param("orderId") Long orderId,
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            @Param("status") String status,
            @Param("handlingStatuses") List<String> handlingStatuses,
            @Param("elapsedCutoffExclusive") LocalDateTime elapsedCutoffExclusive,
            @Param("assigneeFilter") String assigneeFilter,
            @Param("assignedAdminAccountId") Long assignedAdminAccountId,
            @Param("sort") String sort,
            Pageable pageable);

    @Query("""
            SELECT o
            FROM Order o
            LEFT JOIN FETCH o.assignedAdminAccount
            WHERE o.id IN :ids
            """)
    List<Order> findAllWithAssignedAdminByIdIn(
            @Param("ids") List<Long> ids);

    @Query(value = """
            SELECT
                COUNT(*) FILTER (
                    WHERE target_orders.latest_changed_at
                        < CAST(:threeDaysCutoffExclusive AS timestamp)
                ) AS threeDaysOrMoreCount,
                COUNT(*) FILTER (
                    WHERE target_orders.latest_changed_at
                        < CAST(:sevenDaysCutoffExclusive AS timestamp)
                ) AS sevenDaysOrMoreCount
            FROM (
                SELECT
                    o.id,
                    MAX(h.changed_at) AS latest_changed_at
                FROM orders o
                LEFT JOIN order_handling_status_histories h
                    ON h.order_id = o.id
                WHERE o.handling_status IN ('NEEDS_ACTION', 'IN_PROGRESS')
                GROUP BY o.id
            ) target_orders
            """, nativeQuery = true)
    ActionRequiredAgingSummaryProjection findActionRequiredAgingSummary(
            @Param("threeDaysCutoffExclusive") LocalDateTime threeDaysCutoffExclusive,
            @Param("sevenDaysCutoffExclusive") LocalDateTime sevenDaysCutoffExclusive);

}
