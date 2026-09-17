package com.example.ecsite.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.Product;

import jakarta.persistence.LockModeType;

public interface ProductRepository
        extends JpaRepository<Product, Long>,
        JpaSpecificationExecutor<Product> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
            FROM Product p
            WHERE p.id = :id
                    AND p.active = true
            """)
    Optional<Product> findByIdForUpdate(
            @Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
            FROM Product p
            WHERE p.id = :id
            """)
    Optional<Product> findByIdForUpdateIncludingInactive(
            @Param("id") Long id);

    @EntityGraph(attributePaths = "category")
    List<Product> findByActiveTrue();

    @EntityGraph(attributePaths = "category")
    Optional<Product> findByIdAndActiveTrue(Long id);

    @EntityGraph(attributePaths = "category")
    Optional<Product> findByIdAndActiveFalse(Long id);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByActiveTrue(Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByActiveFalse(Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByNameContainingIgnoreCaseAndActiveTrue(
            String keyword,
            Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "category")
    Page<Product> findAll(
            Specification<Product> spec,
            Pageable pageable);

    @EntityGraph(attributePaths = "category")
    List<Product> findByActiveTrueAndStockLessThanEqualOrderByStockAsc(
            int stock);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
            FROM Product p
            WHERE p.id = :id
              AND p.active = false
            """)
    Optional<Product> findInactiveByIdForUpdate(
            @Param("id") Long id);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByCategory_IdAndActiveTrue(
            Long categoryId,
            Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByNameContainingIgnoreCaseAndCategory_IdAndActiveTrue(
            String keyword,
            Long categoryId,
            Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByActiveTrueAndStockGreaterThan(
            int stock,
            Pageable pageable);

    @Query("""
            SELECT p
            FROM Product p
            WHERE p.active = true
              AND p.stock > 0
              AND p.id IN (
                    SELECT i.productId
                    FROM Order o
                    JOIN o.items i
                    WHERE o.status IN (
                          com.example.ecsite.entity.OrderStatus.PAID,
                          com.example.ecsite.entity.OrderStatus.SHIPPED
                    )
                      AND o.orderedAt >= :from
                      AND o.orderedAt < :toExclusive
                    GROUP BY i.productId
              )
            ORDER BY (
                    SELECT SUM(i2.quantity)
                    FROM Order o2
                    JOIN o2.items i2
                    WHERE i2.productId = p.id
                      AND o2.status IN (
                            com.example.ecsite.entity.OrderStatus.PAID,
                            com.example.ecsite.entity.OrderStatus.SHIPPED
                      )
                      AND o2.orderedAt >= :from
                      AND o2.orderedAt < :toExclusive
            ) DESC,
            (
                    SELECT COUNT(DISTINCT o3.id)
                    FROM Order o3
                    JOIN o3.items i3
                    WHERE i3.productId = p.id
                      AND o3.status IN (
                            com.example.ecsite.entity.OrderStatus.PAID,
                            com.example.ecsite.entity.OrderStatus.SHIPPED
                      )
                      AND o3.orderedAt >= :from
                      AND o3.orderedAt < :toExclusive
            ) DESC,
            p.id DESC
            """)
    List<Product> findPopularProducts(
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            Pageable pageable);
}
