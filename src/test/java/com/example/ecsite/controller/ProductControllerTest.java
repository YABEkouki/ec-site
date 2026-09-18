package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;

import com.example.ecsite.entity.Category;
import com.example.ecsite.entity.Product;
import com.example.ecsite.form.ProductSearchForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.CategoryService;
import com.example.ecsite.service.FavoriteService;
import com.example.ecsite.service.ProductService;
import com.example.ecsite.service.ProductViewHistoryService;
import com.example.ecsite.service.ReviewService;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductService productService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private FavoriteService favoriteService;

    @Mock
    private ProductViewHistoryService productViewHistoryService;

    @Test
    void listSearchesProductsAndAddsResultsToModel() {

        ProductSearchForm form = new ProductSearchForm();
        form.setKeyword("キャンプ");
        form.setCategoryId(10L);
        form.setMinPrice(1000);
        form.setMaxPrice(5000);
        form.setInStockOnly(true);
        form.setSort("priceAsc");

        BindingResult bindingResult = new BeanPropertyBindingResult(
                form,
                "searchForm");

        Product product = new Product();
        Page<Product> productPage = new PageImpl<>(List.of(product));

        Category category = new Category();
        List<Category> categories = List.of(category);

        when(productService.searchForUser(
                form,
                2,
                10))
                .thenReturn(productPage);

        when(categoryService.findActiveCategories())
                .thenReturn(categories);

        ProductController controller = new ProductController(
                productService,
                categoryService,
                reviewService,
                favoriteService,
                productViewHistoryService);

        Model model = new ConcurrentModel();

        String view = controller.list(
                form,
                bindingResult,
                2,
                model);

        assertEquals("products/list", view);
        assertSame(
                productPage,
                model.getAttribute("productPage"));
        assertEquals(
                productPage.getContent(),
                model.getAttribute("products"));
        assertSame(
                categories,
                model.getAttribute("categories"));

        verify(productService).searchForUser(
                form,
                2,
                10);
    }

    @Test
    void listDoesNotSearchWhenValidationHasErrors() {

        ProductSearchForm form = new ProductSearchForm();
        form.setMinPrice(-1);

        BindingResult bindingResult = new BeanPropertyBindingResult(
                form,
                "searchForm");

        bindingResult.rejectValue(
                "minPrice",
                "Min",
                "最低価格は0円以上で入力してください。");

        ProductController controller = new ProductController(
                productService,
                categoryService,
                reviewService,
                favoriteService,
                productViewHistoryService);

        Model model = new ConcurrentModel();

        String view = controller.list(
                form,
                bindingResult,
                0,
                model);

        assertEquals("products/list", view);

        Page<?> productPage = (Page<?>) model.getAttribute(
                "productPage");

        assertEquals(0, productPage.getTotalElements());

        verify(
                productService,
                never())
                .searchForUser(
                        any(ProductSearchForm.class),
                        eq(0),
                        eq(10));

        verify(categoryService)
                .findActiveCategories();
    }

    @Test
    void detailRecordsProductViewForAuthenticatedUser() {

        Long productId = 10L;
        Long userId = 20L;

        Product product = new Product();
        product.setId(productId);

        when(productService.findById(productId))
                .thenReturn(product);

        when(reviewService.findByProductId(productId))
                .thenReturn(List.of());

        when(reviewService.countByProductId(productId))
                .thenReturn(0L);

        when(reviewService.getAverageRating(productId))
                .thenReturn(0.0);

        CustomUserDetails userDetails = new CustomUserDetails(
                userId,
                "user1",
                "password",
                true,
                Collections.emptyList());

        ProductController controller = new ProductController(
                productService,
                categoryService,
                reviewService,
                favoriteService,
                productViewHistoryService);

        Model model = new ConcurrentModel();

        String view = controller.detail(
                productId,
                userDetails,
                model);

        assertEquals("products/detail", view);
        assertSame(
                product,
                model.getAttribute("product"));

        verify(productViewHistoryService)
                .recordView(userId, product);
    }

}
