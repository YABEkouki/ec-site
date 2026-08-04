package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

        List<Product> findByActiveTrue();

        Optional<Product> findByIdAndActiveTrue(Long id);

        Optional<Product> findByIdAndActiveFalse(Long id);

        Page<Product> findByActiveTrue(Pageable pageable);

        Page<Product> findByActiveFalse(Pageable pageable);
        
        Page<Product> findByNameContainingIgnoreCaseAndActiveTrue(
                        String keyword,
                        Pageable pageable);

}