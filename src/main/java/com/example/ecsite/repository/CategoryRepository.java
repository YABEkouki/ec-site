package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.Category;

public interface CategoryRepository
        extends JpaRepository<Category, Long> {

    List<Category> findByActiveTrueOrderByDisplayOrderAscNameAsc();

    List<Category> findAllByOrderByDisplayOrderAscNameAsc();

    Optional<Category> findByIdAndActiveTrue(Long id);

    List<Category> findByActiveTrueOrIdOrderByDisplayOrderAscNameAsc(Long id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}