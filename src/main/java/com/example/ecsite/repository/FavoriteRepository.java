package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.Favorite;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    boolean existsByUserIdAndProductId(
            Long userId,
            Long productId);

    Optional<Favorite> findByUserIdAndProductId(
            Long userId,
            Long productId);

    @EntityGraph(attributePaths = {
            "product",
            "product.category"
    })
    List<Favorite> findByUserIdAndProductActiveTrueOrderByCreatedAtDesc(
            Long userId);
}