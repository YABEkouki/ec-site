package com.example.ecsite.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.Product;

public interface ProductRepository
        extends JpaRepository<Product, Long> {

        Page<Product> findByNameContainingIgnoreCase(String keyword, Pageable pageable);

}