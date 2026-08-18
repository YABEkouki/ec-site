package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.Product;

import jakarta.persistence.LockModeType;

public interface ProductRepository
                extends JpaRepository<Product, Long> {

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

}