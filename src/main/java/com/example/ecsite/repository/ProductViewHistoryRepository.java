package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.ProductViewHistory;

public interface ProductViewHistoryRepository
        extends JpaRepository<ProductViewHistory, Long> {

    Optional<ProductViewHistory> findByUserIdAndProductId(
            Long userId,
            Long productId);

    long countByUserId(Long userId);

    List<ProductViewHistory> findByUserIdOrderByLastViewedAtAsc(
            Long userId);

    @Query("""
            SELECT h.product
            FROM ProductViewHistory h
            WHERE h.user.id = :userId
              AND h.product.active = true
              AND h.product.stock > 0
            ORDER BY h.lastViewedAt DESC, h.id DESC
            """)
    List<Product> findRecentAvailableProducts(
            @Param("userId") Long userId,
            Pageable pageable);

}
