package com.example.ecsite.repository;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.StockMovement;

public interface StockMovementRepository
        extends JpaRepository<StockMovement, Long> {

    Page<StockMovement> findByProductIdOrderByChangedAtDescIdDesc(
            Long productId,
            Pageable pageable);

@Query("""
        SELECT sm
        FROM StockMovement sm
        WHERE sm.product.id = :productId
          AND sm.changedAt >= :from
          AND sm.changedAt < :to
          AND LOWER(sm.changedByUsername)
                LIKE LOWER(CONCAT('%', :username, '%'))
        ORDER BY sm.changedAt DESC, sm.id DESC
        """)
Page<StockMovement> searchByProduct(
        @Param("productId") Long productId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        @Param("username") String username,
        Pageable pageable);            
}