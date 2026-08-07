package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Product;
import com.example.ecsite.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Test
    void findLowStockProductsUsesSpecifiedThreshold() {

        int threshold = 5;

        Product product1 = new Product();
        Product product2 = new Product();

        List<Product> expectedProducts =
                List.of(product1, product2);

        when(productRepository
                .findByActiveTrueAndStockLessThanEqualOrderByStockAsc(
                        threshold))
                .thenReturn(expectedProducts);

        ProductService productService =
                new ProductService(productRepository);

        List<Product> actualProducts =
                productService.findLowStockProducts(
                        threshold);

        assertSame(
                expectedProducts,
                actualProducts);

        verify(productRepository)
                .findByActiveTrueAndStockLessThanEqualOrderByStockAsc(
                        threshold);
    }
}