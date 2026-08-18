package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.example.ecsite.entity.Product;
import com.example.ecsite.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

        @Mock
        private ProductRepository productRepository;

        @Mock
        private CategoryService categoryService;

        @Test
        void findLowStockProductsUsesSpecifiedThreshold() {

                int threshold = 5;

                Product product1 = new Product();
                Product product2 = new Product();

                List<Product> expectedProducts = List.of(product1, product2);

                when(productRepository
                                .findByActiveTrueAndStockLessThanEqualOrderByStockAsc(
                                                threshold))
                                .thenReturn(expectedProducts);

                ProductService productService = new ProductService(productRepository, categoryService);

                List<Product> actualProducts = productService.findLowStockProducts(
                                threshold);

                assertSame(
                                expectedProducts,
                                actualProducts);

                verify(productRepository)
                                .findByActiveTrueAndStockLessThanEqualOrderByStockAsc(
                                                threshold);
        }

        @Test
        void deleteUsesLockedProductAndMakesItInactive() {

                Long productId = 1L;

                Product product = mock(Product.class);

                when(productRepository
                                .findByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                ProductService productService = new ProductService(productRepository, categoryService);

                productService.delete(productId);

                verify(productRepository)
                                .findByIdForUpdate(productId);

                verify(product)
                                .setActive(false);
        }

        @Test
        void restoreUsesLockedInactiveProductAndMakesItActive() {

                Long productId = 1L;

                Product product = mock(Product.class);

                when(productRepository
                                .findInactiveByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                ProductService productService = new ProductService(productRepository, categoryService);

                productService.restore(productId);

                verify(productRepository)
                                .findInactiveByIdForUpdate(productId);

                verify(product)
                                .setActive(true);
        }

        @Test
        void searchWithoutConditionsFindsAllActiveProducts() {

                Page<Product> expected = new PageImpl<>(List.of(new Product()));

                when(productRepository.findByActiveTrue(
                                any(Pageable.class)))
                                .thenReturn(expected);

                ProductService productService = new ProductService(
                                productRepository,
                                categoryService);

                Page<Product> actual = productService.search(
                                null,
                                null,
                                0,
                                10,
                                "newest");

                assertSame(expected, actual);

                verify(productRepository)
                                .findByActiveTrue(
                                                any(Pageable.class));
        }

        @Test
        void searchWithKeywordUsesKeywordQuery() {

                Page<Product> expected = new PageImpl<>(List.of(new Product()));

                when(productRepository
                                .findByNameContainingIgnoreCaseAndActiveTrue(
                                                eq("商品"),
                                                any(Pageable.class)))
                                .thenReturn(expected);

                ProductService productService = new ProductService(
                                productRepository,
                                categoryService);

                Page<Product> actual = productService.search(
                                "  商品  ",
                                null,
                                0,
                                10,
                                "newest");

                assertSame(expected, actual);

                verify(productRepository)
                                .findByNameContainingIgnoreCaseAndActiveTrue(
                                                eq("商品"),
                                                any(Pageable.class));
        }

        @Test
        void searchWithCategoryUsesCategoryQuery() {

                Page<Product> expected = new PageImpl<>(List.of(new Product()));

                when(productRepository
                                .findByCategory_IdAndActiveTrue(
                                                eq(2L),
                                                any(Pageable.class)))
                                .thenReturn(expected);

                ProductService productService = new ProductService(
                                productRepository,
                                categoryService);

                Page<Product> actual = productService.search(
                                null,
                                2L,
                                0,
                                10,
                                "newest");

                assertSame(expected, actual);

                verify(productRepository)
                                .findByCategory_IdAndActiveTrue(
                                                eq(2L),
                                                any(Pageable.class));
        }

        @Test
        void searchWithKeywordAndCategoryUsesCombinedQuery() {

                Page<Product> expected = new PageImpl<>(List.of(new Product()));

                when(productRepository
                                .findByNameContainingIgnoreCaseAndCategory_IdAndActiveTrue(
                                                eq("商品"),
                                                eq(2L),
                                                any(Pageable.class)))
                                .thenReturn(expected);

                ProductService productService = new ProductService(
                                productRepository,
                                categoryService);

                Page<Product> actual = productService.search(
                                "  商品  ",
                                2L,
                                0,
                                10,
                                "priceAsc");

                assertSame(expected, actual);

                verify(productRepository)
                                .findByNameContainingIgnoreCaseAndCategory_IdAndActiveTrue(
                                                eq("商品"),
                                                eq(2L),
                                                any(Pageable.class));
        }

}