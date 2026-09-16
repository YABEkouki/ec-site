package com.example.ecsite.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.ProductSearchKeyword;

public interface ProductSearchKeywordRepository
        extends JpaRepository<ProductSearchKeyword, Long> {

    List<ProductSearchKeyword> findByProduct_IdOrderByIdAsc(Long productId);
}
