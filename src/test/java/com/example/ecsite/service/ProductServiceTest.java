package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.mock.web.MockMultipartFile;

import com.example.ecsite.entity.Category;
import com.example.ecsite.entity.Product;
import com.example.ecsite.exception.CategoryNotFoundException;
import com.example.ecsite.form.ProductForm;
import com.example.ecsite.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

        @Mock
        private ProductRepository productRepository;

        @Mock
        private CategoryService categoryService;

        @Mock
        private ProductImageService productImageService;

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

                ProductService productService = new ProductService(productRepository, categoryService,
                                productImageService);

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

                ProductService productService = new ProductService(productRepository, categoryService,
                                productImageService);

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

                ProductService productService = new ProductService(productRepository, categoryService,
                                productImageService);

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
                                categoryService,
                                productImageService);

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
                                categoryService,
                                productImageService);

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
                                categoryService,
                                productImageService);

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
                                categoryService,
                                productImageService);

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

        @Test
        void updateAllowsKeepingCurrentInactiveCategory() {

                Long productId = 1L;
                Long categoryId = 2L;

                Product product = mock(Product.class);
                Category currentCategory = mock(Category.class);

                ProductForm form = new ProductForm();
                form.setName("更新商品");
                form.setPrice(1000);
                form.setStock(5);
                form.setDescription("更新後の説明");
                form.setCategoryId(categoryId);

                when(productRepository
                                .findByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                when(product.getCategory())
                                .thenReturn(currentCategory);

                when(currentCategory.getId())
                                .thenReturn(categoryId);

                when(categoryService.findById(categoryId))
                                .thenReturn(currentCategory);

                ProductService productService = new ProductService(
                                productRepository,
                                categoryService,
                                productImageService);

                Product result = productService.update(
                                productId,
                                form);

                assertSame(product, result);

                verify(categoryService)
                                .findById(categoryId);

                verify(categoryService, never())
                                .findActiveById(categoryId);

                verify(product)
                                .setCategory(currentCategory);
        }

        @Test
        void updateAllowsChangingToActiveCategory() {

                Long productId = 1L;
                Long currentCategoryId = 2L;
                Long requestedCategoryId = 3L;

                Product product = mock(Product.class);
                Category currentCategory = mock(Category.class);
                Category requestedCategory = mock(Category.class);

                ProductForm form = new ProductForm();
                form.setName("更新商品");
                form.setPrice(1000);
                form.setStock(5);
                form.setDescription("更新後の説明");
                form.setCategoryId(requestedCategoryId);

                when(productRepository
                                .findByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                when(product.getCategory())
                                .thenReturn(currentCategory);

                when(currentCategory.getId())
                                .thenReturn(currentCategoryId);

                when(categoryService
                                .findActiveById(requestedCategoryId))
                                .thenReturn(requestedCategory);

                ProductService productService = new ProductService(
                                productRepository,
                                categoryService,
                                productImageService);

                Product result = productService.update(
                                productId,
                                form);

                assertSame(product, result);

                verify(categoryService)
                                .findActiveById(
                                                requestedCategoryId);

                verify(categoryService, never())
                                .findById(requestedCategoryId);

                verify(product)
                                .setCategory(requestedCategory);
        }

        @Test
        void updateRejectsChangingToInactiveCategory() {

                Long productId = 1L;
                Long currentCategoryId = 2L;
                Long requestedCategoryId = 3L;

                Product product = mock(Product.class);
                Category currentCategory = mock(Category.class);

                ProductForm form = new ProductForm();
                form.setName("更新商品");
                form.setPrice(1000);
                form.setStock(5);
                form.setDescription("更新後の説明");
                form.setCategoryId(requestedCategoryId);

                when(productRepository
                                .findByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                when(product.getCategory())
                                .thenReturn(currentCategory);

                when(currentCategory.getId())
                                .thenReturn(currentCategoryId);

                when(categoryService
                                .findActiveById(requestedCategoryId))
                                .thenThrow(
                                                new CategoryNotFoundException(
                                                                requestedCategoryId));

                ProductService productService = new ProductService(
                                productRepository,
                                categoryService,
                                productImageService);

                assertThrows(
                                CategoryNotFoundException.class,
                                () -> productService.update(
                                                productId,
                                                form));

                verify(categoryService)
                                .findActiveById(
                                                requestedCategoryId);

                verify(categoryService, never())
                                .findById(requestedCategoryId);

                verify(product, never())
                                .setName(any());

                verify(product, never())
                                .setCategory(any());
        }

        @Test
        void createSavesProductImageAndSetsImagePath() {

                Long productId = 1L;
                Long categoryId = 2L;

                Category category = new Category();

                ProductForm form = new ProductForm();
                form.setName("テスト商品");
                form.setPrice(1000);
                form.setStock(5);
                form.setDescription("説明");
                form.setCategoryId(categoryId);

                MockMultipartFile imageFile = new MockMultipartFile(
                                "imageFile",
                                "product.jpg",
                                "image/jpeg",
                                "test image".getBytes());

                form.setImageFile(imageFile);

                when(categoryService.findActiveById(categoryId))
                                .thenReturn(category);

                when(productRepository.save(any(Product.class)))
                                .thenAnswer(invocation -> {
                                        Product product = invocation.getArgument(0);
                                        product.setId(productId);
                                        return product;
                                });

                when(productImageService.saveImage(productId, imageFile))
                                .thenReturn("1/test.jpg");

                ProductService productService = new ProductService(
                                productRepository,
                                categoryService,
                                productImageService);

                Product result = productService.create(form);

                verify(productImageService)
                                .saveImage(productId, imageFile);

                verify(productRepository)
                                .save(any(Product.class));

                assertSame(category, result.getCategory());
                assertEquals("1/test.jpg", result.getImagePath());
        }

        @Test
        void updateReplacesProductImage() {

                Long productId = 1L;
                Long categoryId = 2L;

                Product product = mock(Product.class);
                Category category = mock(Category.class);

                ProductForm form = new ProductForm();
                form.setName("更新商品");
                form.setPrice(1000);
                form.setStock(5);
                form.setDescription("更新後の説明");
                form.setCategoryId(categoryId);

                MockMultipartFile imageFile = new MockMultipartFile(
                                "imageFile",
                                "new-image.jpg",
                                "image/jpeg",
                                "new image".getBytes());

                form.setImageFile(imageFile);

                when(productRepository.findByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                when(product.getId())
                                .thenReturn(productId);

                when(product.getCategory())
                                .thenReturn(category);

                when(category.getId())
                                .thenReturn(categoryId);

                when(categoryService.findById(categoryId))
                                .thenReturn(category);

                when(product.getImagePath())
                                .thenReturn("1/old-image.jpg");

                when(productImageService.saveImage(productId, imageFile))
                                .thenReturn("1/new-image.jpg");

                ProductService productService = new ProductService(
                                productRepository,
                                categoryService,
                                productImageService);

                Product result = productService.update(
                                productId,
                                form);

                assertSame(product, result);

                verify(productImageService)
                                .saveImage(productId, imageFile);

                verify(product)
                                .setImagePath("1/new-image.jpg");

                verify(productImageService)
                                .deleteImage("1/old-image.jpg");
        }

        @Test
        void updateKeepsExistingImageWhenNewImageIsNotSelected() {

                Long productId = 1L;
                Long categoryId = 2L;

                Product product = mock(Product.class);
                Category category = mock(Category.class);

                ProductForm form = new ProductForm();
                form.setName("更新商品");
                form.setPrice(1000);
                form.setStock(5);
                form.setDescription("更新後の説明");
                form.setCategoryId(categoryId);

                when(productRepository.findByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                when(product.getCategory())
                                .thenReturn(category);

                when(category.getId())
                                .thenReturn(categoryId);

                when(categoryService.findById(categoryId))
                                .thenReturn(category);

                ProductService productService = new ProductService(
                                productRepository,
                                categoryService,
                                productImageService);

                Product result = productService.update(
                                productId,
                                form);

                assertSame(product, result);

                verify(productImageService, never())
                                .saveImage(any(), any());

                verify(product, never())
                                .setImagePath(any());

                verify(productImageService, never())
                                .deleteImage(any());
        }
}