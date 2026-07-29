package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}