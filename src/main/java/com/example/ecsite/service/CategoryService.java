package com.example.ecsite.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Category;
import com.example.ecsite.exception.CategoryAlreadyExistsException;
import com.example.ecsite.exception.CategoryNotFoundException;
import com.example.ecsite.form.CategoryForm;
import com.example.ecsite.repository.CategoryRepository;

@Service
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(
            CategoryRepository categoryRepository) {

        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public List<Category> findActiveCategories() {

        return categoryRepository
                .findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Category findActiveById(Long id) {

        return categoryRepository
                .findByIdAndActiveTrue(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
    }

    public Category create(CategoryForm categoryForm) {

        String name = normalizeName(categoryForm.getName());

        if (categoryRepository
                .existsByNameIgnoreCase(name)) {

            throw new CategoryAlreadyExistsException(
                    name);
        }

        Category category = new Category(name);

        try {
            return categoryRepository
                    .saveAndFlush(category);

        } catch (DataIntegrityViolationException e) {

            throw new CategoryAlreadyExistsException(
                    name,
                    e);
        }
    }

    private String normalizeName(String name) {
        return name.trim();
    }
}