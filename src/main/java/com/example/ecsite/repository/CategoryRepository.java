package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.Category;

public interface CategoryRepository
        extends JpaRepository<Category, Long> {

    List<Category> findByActiveTrueOrderByNameAsc();

    List<Category> findAllByOrderByActiveDescNameAsc();

    Optional<Category> findByIdAndActiveTrue(Long id);

    List<Category> findByActiveTrueOrIdOrderByNameAsc(Long id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}