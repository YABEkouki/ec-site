package com.example.ecsite.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Category;
import com.example.ecsite.entity.Product;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.form.ProductForm;
import com.example.ecsite.mapper.ProductMapper;
import com.example.ecsite.repository.ProductRepository;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryService categoryService;
    private final ProductImageService productImageService;

    public ProductService(
            ProductRepository productRepository,
            CategoryService categoryService,
            ProductImageService productImageService) {

        this.productRepository = productRepository;
        this.categoryService = categoryService;
        this.productImageService = productImageService;
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
    public Page<Product> findInactiveProducts(int page, int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Product::getId).descending());

        return productRepository.findByActiveFalse(pageable);
    }

    public Product create(ProductForm productForm) {

        Product product = ProductMapper.toEntity(productForm);

        product.setCategory(categoryService.findActiveById(productForm.getCategoryId()));

        product = productRepository.save(product);

        String imagePath = productImageService.saveImage(
                product.getId(),
                productForm.getImageFile());

        product.setImagePath(imagePath);

        return product;
    }

    public Product update(
            Long id,
            ProductForm productForm) {

        Product product = findByIdForUpdate(id);

        Long currentCategoryId = product.getCategory().getId();

        Long requestedCategoryId = productForm.getCategoryId();

        Category category;

        if (currentCategoryId.equals(requestedCategoryId)) {

            category = categoryService
                    .findById(requestedCategoryId);

        } else {

            category = categoryService
                    .findActiveById(requestedCategoryId);
        }

        ProductMapper.copyToEntity(productForm, product);

        product.setCategory(category);

        if (productForm.getImageFile() != null
                && !productForm.getImageFile().isEmpty()) {

            String oldImagePath = product.getImagePath();

            String newImagePath = productImageService.saveImage(
                    product.getId(),
                    productForm.getImageFile());

            product.setImagePath(newImagePath);

            productImageService.deleteImage(oldImagePath);
        }
        return product;
    }

    public void delete(Long id) {

        Product product = findByIdForUpdate(id);

        product.setActive(false);
    }

    @Transactional(readOnly = true)
    public Page<Product> search(
            String keyword,
            int page,
            int size,
            String sort) {

        Sort sortCondition = createSort(sort);

        Pageable pageable = PageRequest.of(page, size, sortCondition);

        if (keyword == null || keyword.isBlank()) {
            return productRepository.findByActiveTrue(pageable);
        }

        return productRepository
                .findByNameContainingIgnoreCaseAndActiveTrue(keyword.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<Product> search(
            String keyword,
            Long categoryId,
            int page,
            int size,
            String sort) {

        Sort sortCondition = createSort(sort);

        Pageable pageable = PageRequest.of(
                page,
                size,
                sortCondition);

        String normalizedKeyword = keyword == null ? "" : keyword.trim();

        boolean hasKeyword = !normalizedKeyword.isBlank();
        boolean hasCategory = categoryId != null;

        if (hasKeyword && hasCategory) {

            return productRepository
                    .findByNameContainingIgnoreCaseAndCategory_IdAndActiveTrue(
                            normalizedKeyword,
                            categoryId,
                            pageable);
        }

        if (hasKeyword) {

            return productRepository
                    .findByNameContainingIgnoreCaseAndActiveTrue(
                            normalizedKeyword,
                            pageable);
        }

        if (hasCategory) {

            return productRepository
                    .findByCategory_IdAndActiveTrue(
                            categoryId,
                            pageable);
        }

        return productRepository
                .findByActiveTrue(pageable);
    }

    private Sort createSort(String sort) {

        return switch (sort) {
            case "nameAsc" -> Sort.by(Product::getName).ascending();

            case "priceAsc" -> Sort.by(Product::getPrice).ascending();

            case "priceDesc" -> Sort.by(Product::getPrice).descending();

            case "newest" -> Sort.by(Product::getId).descending();

            default -> Sort.by(Product::getId).descending();
        };
    }

    public void restore(Long id) {

        Product product = productRepository
                .findInactiveByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        product.setActive(true);
    }

    @Transactional(readOnly = true)
    public List<Product> findLowStockProducts(int threshold) {

        return productRepository
                .findByActiveTrueAndStockLessThanEqualOrderByStockAsc(
                        threshold);
    }
}