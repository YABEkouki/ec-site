package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.example.ecsite.entity.Product;
import com.example.ecsite.repository.ProductRepository;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.form.ProductForm;
import com.example.ecsite.mapper.ProductMapper;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() ->
                        new ProductNotFoundException(id));
    }

    public Product create(ProductForm productform) {

        Product product = ProductMapper.toEntity(productform);

        return productRepository.save(product);
    }

    public Product update(Long id, ProductForm productForm) {
        Product product = findById(id);

        ProductMapper.copyToEntity(productForm, product);
     
        return product;
    }

    public void delete(Long id) {
        Product product = findById(id);
        productRepository.delete(product);
    }

    @Transactional(readOnly = true)
    public Page<Product> search(String keyword, int page, int size, String sort) {

    Sort sortCondition = createSort(sort);

    Pageable pageable = PageRequest.of(
            page,
            size,
            sortCondition);

        if (keyword == null || keyword.isBlank()) {
            return productRepository.findAll(pageable);
        }

        return productRepository
                .findByNameContainingIgnoreCase(keyword.trim(), pageable);
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
}