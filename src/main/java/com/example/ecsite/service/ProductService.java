package com.example.ecsite.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Product;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.form.ProductForm;
import com.example.ecsite.mapper.ProductMapper;
import com.example.ecsite.repository.ProductRepository;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {

        this.productRepository = productRepository;
    }

    public List<Product> findAll() {
        return productRepository.findByActiveTrue();
    }

    public Product findById(Long id) {

        return productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    public Product findByIdForUpdate(Long id) {

        return productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    public Product findByIdForUpdateIncludingInactive(Long id) {

        return productRepository
                .findByIdForUpdateIncludingInactive(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Product> findInactiveProducts(
            int page,
            int size) {

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by("id").descending());

        return productRepository.findByActiveFalse(pageable);
    }

    public Product create(ProductForm productform) {

        Product product = ProductMapper.toEntity(productform);

        return productRepository.save(product);
    }

    public Product update(Long id, ProductForm productForm) {

        Product product = findByIdForUpdate(id);

        ProductMapper.copyToEntity(productForm, product);

        return product;
    }

    public void delete(Long id) {

        Product product = findByIdForUpdate(id);

        product.setActive(false);
    }

    @Transactional(readOnly = true)
    public Page<Product> search(String keyword, int page, int size, String sort) {

        Sort sortCondition = createSort(sort);

        Pageable pageable = PageRequest.of(
                page,
                size,
                sortCondition);

        if (keyword == null || keyword.isBlank()) {
            return productRepository.findByActiveTrue(pageable);
        }

        return productRepository
                .findByNameContainingIgnoreCaseAndActiveTrue(keyword.trim(), pageable);
    }

    private Sort createSort(String sort) {

        return switch (sort) {
            case "nameAsc" -> Sort.by("name").ascending();
            case "priceAsc" -> Sort.by("price").ascending();
            case "priceDesc" -> Sort.by("price").descending();
            case "newest" -> Sort.by("id").descending();
            default -> Sort.by("id").descending(); // Default sort order
        };
    }

    public void restore(Long id) {

        Product product = productRepository
                .findInactiveByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        product.setActive(true);
    }

    @Transactional(readOnly = true)
    public List<Product> findLowStockProducts(
            int threshold) {

        return productRepository
                .findByActiveTrueAndStockLessThanEqualOrderByStockAsc(
                        threshold);
    }
}