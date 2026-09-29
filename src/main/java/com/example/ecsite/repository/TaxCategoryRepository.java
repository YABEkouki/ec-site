package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.TaxCategory;

public interface TaxCategoryRepository
        extends JpaRepository<TaxCategory, Long> {

    List<TaxCategory> findByActiveTrueOrderByDisplayOrderAscIdAsc();

    Optional<TaxCategory> findByIdAndActiveTrue(Long id);

    Optional<TaxCategory> findByCode(String code);
}
