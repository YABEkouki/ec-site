package com.example.ecsite.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.Review;
import com.example.ecsite.form.ProductSearchForm;
import com.example.ecsite.form.ReviewForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.CategoryService;
import com.example.ecsite.service.FavoriteService;
import com.example.ecsite.service.ProductService;
import com.example.ecsite.service.ProductViewHistoryService;
import com.example.ecsite.service.ReviewService;

import jakarta.validation.Valid;

@Controller
public class ProductController {

    private final ProductService productService;
    private final CategoryService categoryService;
    private final ReviewService reviewService;
    private final FavoriteService favoriteService;
    private final ProductViewHistoryService productViewHistoryService;

    public ProductController(
            ProductService productService,
            CategoryService categoryService,
            ReviewService reviewService,
            FavoriteService favoriteService,
            ProductViewHistoryService productViewHistoryService) {

        this.productService = productService;
        this.categoryService = categoryService;
        this.reviewService = reviewService;
        this.favoriteService = favoriteService;
        this.productViewHistoryService = productViewHistoryService;
    }

    @GetMapping("/products")
    public String list(
            @Valid @ModelAttribute("searchForm") ProductSearchForm searchForm,
            BindingResult bindingResult,
            @RequestParam(name = "page", defaultValue = "0") int page,
            Model model) {

        int size = 10; // Number of products per page

        Page<Product> productPage;

        if (bindingResult.hasErrors()) {

            productPage = Page.empty(
                    PageRequest.of(page, size));

        } else {

            productPage = productService.searchForUser(
                    searchForm,
                    page,
                    size);
        }

        model.addAttribute("products", productPage.getContent());
        model.addAttribute("productPage", productPage);
        model.addAttribute(
                "categories",
                categoryService.findActiveCategories());

        return "products/list";
    }

    @GetMapping("/products/{id}")
    public String detail(@PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Product product = productService.findById(id);

        if (userDetails != null) {
            productViewHistoryService.recordView(
                    userDetails.getId(),
                    product);
        }

        List<Review> reviews = reviewService.findByProductId(id);

        long reviewCount = reviewService.countByProductId(id);

        double averageRating = reviewService.getAverageRating(id);

        Optional<Review> myReview = Optional.empty();

        if (userDetails != null) {
            myReview = reviewService.findByProductIdAndUserId(
                    id,
                    userDetails.getId());
        }

        ReviewForm editReviewForm = null;

        if (myReview.isPresent()) {

            Review review = myReview.get();

            editReviewForm = new ReviewForm();
            editReviewForm.setRating(review.getRating());
            editReviewForm.setComment(review.getComment());
        }

        boolean isFavorite = false;

        if (userDetails != null) {
            isFavorite = favoriteService.isFavorite(
                    id,
                    userDetails.getId());
        }

        model.addAttribute("product", product);
        model.addAttribute("reviews", reviews);
        model.addAttribute("reviewCount", reviewCount);
        model.addAttribute("averageRating", averageRating);
        model.addAttribute("myReview", myReview);
        model.addAttribute("reviewForm", new ReviewForm());
        model.addAttribute("editReviewForm", editReviewForm);
        model.addAttribute("isFavorite", isFavorite);

        return "products/detail";
    }

}
