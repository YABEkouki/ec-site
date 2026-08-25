package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    
    @EntityGraph(attributePaths = "user")
    List<Review> findByProductIdOrderByCreatedAtDesc(Long productId);

    boolean existsByProductIdAndUserId(Long productId, Long userId);

    Optional<Review> findByProductIdAndUserId(Long productId, Long userId);

    long countByProductId(Long productId);

    @Query("""
            SELECT AVG(r.rating)
            FROM Review r
            WHERE r.product.id = :productId
            """)
    Double findAverageRatingByProductId(@Param("productId") Long productId);
}