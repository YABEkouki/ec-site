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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;

import com.example.ecsite.entity.Category;
import com.example.ecsite.entity.Product;
import com.example.ecsite.exception.CategoryNotFoundException;
import com.example.ecsite.form.ProductForm;
import com.example.ecsite.form.ProductSearchForm;
import com.example.ecsite.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryService categoryService;

    @Mock
    private ProductImageService productImageService;

    @Mock
    private ProductSearchKeywordService productSearchKeywordService;

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

        ProductService productService = new ProductService(
                productRepository,
                categoryService,
                productImageService,
                productSearchKeywordService);

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

        ProductService productService = new ProductService(
                productRepository,
                categoryService,
                productImageService,
                productSearchKeywordService);

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

        ProductService productService = new ProductService(
                productRepository,
                categoryService,
                productImageService,
                productSearchKeywordService);

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
                productImageService,
                productSearchKeywordService);

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
                productImageService,
                productSearchKeywordService);

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
                productImageService,
                productSearchKeywordService);

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
                productImageService,
                productSearchKeywordService);

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
                productImageService,
                productSearchKeywordService);

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
                productImageService,
                productSearchKeywordService);

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
                productImageService,
                productSearchKeywordService);

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
                productImageService,
                productSearchKeywordService);

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
                productImageService,
                productSearchKeywordService);

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
                productImageService,
                productSearchKeywordService);

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

    @Test
    void updateDoesNotOverwriteStock() {

        Long productId = 1L;
        Long categoryId = 2L;

        Product product = mock(Product.class);
        Category category = mock(Category.class);

        ProductForm form = new ProductForm();
        form.setName("更新商品");
        form.setPrice(1000);
        form.setStock(999);
        form.setDescription("更新後の説明");
        form.setCategoryId(categoryId);

        when(productRepository
                .findByIdForUpdate(productId))
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
                productImageService,
                productSearchKeywordService);

        productService.update(
                productId,
                form);

        verify(product, never())
                .setStock(any());
    }

    @Test
    void createSyncsSearchKeywords() {

        Long productId = 1L;
        Long categoryId = 2L;

        Category category = new Category();

        ProductForm form = new ProductForm();
        form.setName("テスト商品");
        form.setPrice(1000);
        form.setStock(5);
        form.setDescription("説明");
        form.setCategoryId(categoryId);
        form.setSearchKeywords(
                List.of("キャンプ", "軽量"));

        when(categoryService.findActiveById(categoryId))
                .thenReturn(category);

        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> {
                    Product product = invocation.getArgument(0);
                    product.setId(productId);
                    return product;
                });

        ProductService productService = new ProductService(
                productRepository,
                categoryService,
                productImageService,
                productSearchKeywordService);

        Product result = productService.create(form);

        verify(productSearchKeywordService)
                .syncKeywords(
                        result,
                        form.getSearchKeywords());
    }

    @Test
    void updateSyncsSearchKeywords() {

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
        form.setSearchKeywords(
                List.of("アウトドア", "防水"));

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
                productImageService,
                productSearchKeywordService);

        Product result = productService.update(
                productId,
                form);

        verify(productSearchKeywordService)
                .syncKeywords(
                        result,
                        form.getSearchKeywords());
    }

    @Test
    void findSearchKeywordsDelegatesToProductSearchKeywordService() {

        Long productId = 1L;

        List<String> expected = List.of("キャンプ", "軽量");

        when(productSearchKeywordService.findKeywords(productId))
                .thenReturn(expected);

        ProductService productService = new ProductService(
                productRepository,
                categoryService,
                productImageService,
                productSearchKeywordService);

        List<String> actual = productService.findSearchKeywords(productId);

        assertSame(expected, actual);

        verify(productSearchKeywordService)
                .findKeywords(productId);
    }

    @Test
    void searchForUserUsesAllSearchConditions() {

        ProductSearchForm form = new ProductSearchForm();
        form.setKeyword("キャンプ 軽量");
        form.setCategoryId(10L);
        form.setMinPrice(1000);
        form.setMaxPrice(5000);
        form.setInStockOnly(true);
        form.setSort("priceAsc");

        Page<Product> expected = new PageImpl<>(List.of());

        when(productRepository.findAll(
                ArgumentMatchers.<Specification<Product>>any(),
                any(Pageable.class)))
                .thenReturn(expected);

        ProductService productService = new ProductService(
                productRepository,
                categoryService,
                productImageService,
                productSearchKeywordService);

        Page<Product> actual = productService.searchForUser(
                form,
                2,
                10);

        assertSame(expected, actual);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(productRepository).findAll(
                ArgumentMatchers.<Specification<Product>>any(),
                pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();

        assertEquals(2, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());

        assertEquals(
                Sort.by(
                        Sort.Order.asc("price"),
                        Sort.Order.asc("id")),
                pageable.getSort());
    }

    @Test
    void searchForUserUsesNewestSortByDefault() {

        ProductSearchForm form = new ProductSearchForm();
        form.setSort("newest");

        when(productRepository.findAll(
                ArgumentMatchers.<Specification<Product>>any(),
                any(Pageable.class)))
                .thenReturn(Page.empty());

        ProductService productService = new ProductService(
                productRepository,
                categoryService,
                productImageService,
                productSearchKeywordService);

        productService.searchForUser(
                form,
                0,
                10);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(productRepository).findAll(
                ArgumentMatchers.<Specification<Product>>any(),
                pageableCaptor.capture());

        assertEquals(
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")),
                pageableCaptor.getValue().getSort());
    }

    @Test
    void searchForUserUsesNameAscendingSort() {

        assertUserSearchSort(
                "nameAsc",
                Sort.by(
                        Sort.Order.asc("name"),
                        Sort.Order.asc("id")));
    }

    @Test
    void searchForUserUsesPriceDescendingSort() {

        assertUserSearchSort(
                "priceDesc",
                Sort.by(
                        Sort.Order.desc("price"),
                        Sort.Order.desc("id")));
    }

    @Test
    void searchForUserFallsBackToNewestForUnknownSort() {

        assertUserSearchSort(
                "unknown",
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")));
    }

    @Test
    void findLatestAvailableProductsUsesAvailableConditionAndNewestSort() {

        int limit = 5;

        Product product1 = new Product();
        Product product2 = new Product();

        Page<Product> page = new PageImpl<>(
                List.of(product1, product2));

        when(productRepository
                .findByActiveTrueAndStockGreaterThan(
                        eq(0),
                        any(Pageable.class)))
                .thenReturn(page);

        ProductService productService = new ProductService(
                productRepository,
                categoryService,
                productImageService,
                productSearchKeywordService);

        List<Product> result = productService.findLatestAvailableProducts(limit);

        assertEquals(
                List.of(product1, product2),
                result);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(productRepository)
                .findByActiveTrueAndStockGreaterThan(
                        eq(0),
                        pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();

        assertEquals(0, pageable.getPageNumber());
        assertEquals(limit, pageable.getPageSize());

        assertEquals(
                Sort.Direction.DESC,
                pageable.getSort()
                        .getOrderFor("createdAt")
                        .getDirection());

        assertEquals(
                Sort.Direction.DESC,
                pageable.getSort()
                        .getOrderFor("id")
                        .getDirection());
    }

    private void assertUserSearchSort(
            String sort,
            Sort expectedSort) {

        ProductSearchForm form = new ProductSearchForm();
        form.setSort(sort);

        when(productRepository.findAll(
                ArgumentMatchers.<Specification<Product>>any(),
                any(Pageable.class)))
                .thenReturn(Page.empty());

        ProductService productService = new ProductService(
                productRepository,
                categoryService,
                productImageService,
                productSearchKeywordService);

        productService.searchForUser(
                form,
                0,
                10);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(productRepository).findAll(
                ArgumentMatchers.<Specification<Product>>any(),
                pageableCaptor.capture());

        assertEquals(
                expectedSort,
                pageableCaptor.getValue().getSort());
    }

    @Test
    void findPopularProductsUsesRecentThirtyDaysAndSpecifiedLimit() {

        ZoneId zoneId = ZoneId.of("Asia/Tokyo");

        Clock clock = Clock.fixed(
                Instant.parse("2026-09-17T03:00:00Z"),
                zoneId);

        Product product1 = new Product();
        Product product2 = new Product();

        List<Product> expected = List.of(
                product1,
                product2);

        when(productRepository.findPopularProducts(
                eq(LocalDateTime.of(
                        2026, 8, 19, 0, 0)),
                eq(LocalDateTime.of(
                        2026, 9, 18, 0, 0)),
                any(Pageable.class)))
                .thenReturn(expected);

        ProductService productService = new ProductService(
                productRepository,
                categoryService,
                productImageService,
                productSearchKeywordService,
                clock);

        List<Product> actual = productService
                .findPopularProducts(5);

        assertSame(expected, actual);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(productRepository).findPopularProducts(
                eq(LocalDateTime.of(
                        2026, 8, 19, 0, 0)),
                eq(LocalDateTime.of(
                        2026, 9, 18, 0, 0)),
                pageableCaptor.capture());

        assertEquals(
                5,
                pageableCaptor.getValue().getPageSize());

        assertEquals(
                0,
                pageableCaptor.getValue().getPageNumber());
    }

}
