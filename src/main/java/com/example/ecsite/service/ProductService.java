package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.Product;
import com.example.ecsite.repository.ProductRepository;

@Service
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
                        new IllegalArgumentException("商品が存在しません。"));
    }

    public Product save(Product product) {
        return productRepository.save(product);
    }

    public Product create(Product product) {
        return productRepository.save(product);
    }

    public Product update(Long id, Product formProduct) {
        Product existingProduct = findById(id);

        existingProduct.setName(formProduct.getName());
        existingProduct.setPrice(formProduct.getPrice());
        existingProduct.setStock(formProduct.getStock());
        existingProduct.setDescription(formProduct.getDescription());

        return productRepository.save(existingProduct);
    }

    public void delete(Long id) {
        productRepository.deleteById(id);
    }
}