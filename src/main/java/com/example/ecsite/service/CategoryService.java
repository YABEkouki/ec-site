package com.example.ecsite.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Category;
import com.example.ecsite.exception.CategoryAlreadyExistsException;
import com.example.ecsite.exception.CategoryNotFoundException;
import com.example.ecsite.exception.ProtectedCategoryException;
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
                .findByActiveTrueOrderByDisplayOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Category> findCategoriesForProductEdit(
            Long currentCategoryId) {

        return categoryRepository
                .findByActiveTrueOrIdOrderByDisplayOrderAscNameAsc(
                        currentCategoryId);
    }

    @Transactional(readOnly = true)
    public List<Category> findAllCategories() {

        return categoryRepository
                .findAllByOrderByDisplayOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public Category findById(Long id) {

        return categoryRepository
                .findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
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
        category.setDisplayOrder(
                categoryForm.getDisplayOrder());

        try {
            return categoryRepository
                    .saveAndFlush(category);

        } catch (DataIntegrityViolationException e) {

            throw new CategoryAlreadyExistsException(
                    name,
                    e);
        }
    }

    public Category update(
            Long id,
            CategoryForm categoryForm) {

        Category category = findById(id);

        String name = normalizeName(categoryForm.getName());

        if (category.isSystemCategory()
                && !category.getName().equals(name)) {

            throw new ProtectedCategoryException(id);
        }

        if (categoryRepository
                .existsByNameIgnoreCaseAndIdNot(
                        name,
                        id)) {

            throw new CategoryAlreadyExistsException(
                    name);
        }

        category.setName(name);

        if (category.isSystemCategory()) {

            category.setDisplayOrder(9999);

        } else {

            category.setDisplayOrder(
                    categoryForm.getDisplayOrder());
        }

        try {
            return categoryRepository
                    .saveAndFlush(category);

        } catch (DataIntegrityViolationException e) {

            throw new CategoryAlreadyExistsException(
                    name,
                    e);
        }
    }

    public Category deactivate(Long id) {

        Category category = findById(id);

        if (category.isSystemCategory()) {

            throw new ProtectedCategoryException(id);
        }

        category.setActive(false);

        return categoryRepository
                .saveAndFlush(category);
    }

    public Category activate(Long id) {

        Category category = findById(id);

        category.setActive(true);

        return categoryRepository
                .saveAndFlush(category);
    }

    private String normalizeName(String name) {
        return name.trim();
    }
}