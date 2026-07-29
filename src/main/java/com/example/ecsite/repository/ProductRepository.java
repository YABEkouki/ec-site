package com.example.ecsite.repository;

import java.util.List;

import com.example.ecsite.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductRepository
        extends JpaRepository<Product, Long> {

        Page<Product> findByNameContainingIgnoreCase(String keyword, Pageable pageable);

}