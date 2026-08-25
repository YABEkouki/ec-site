package com.example.ecsite.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.Review;
import com.example.ecsite.exception.ReviewAlreadyExistsException;
import com.example.ecsite.form.ReviewForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.ProductService;
import com.example.ecsite.service.ReviewService;

import jakarta.validation.Valid;

@Controller
public class ReviewController {

        private final ReviewService reviewService;
        private final ProductService productService;

        public ReviewController(ReviewService reviewService, ProductService productService) {
                this.reviewService = reviewService;
                this.productService = productService;
        }

        @PostMapping("/products/{productId}/reviews")
        public String create(
                        @PathVariable Long productId,
                        @Valid @ModelAttribute("reviewForm") ReviewForm reviewForm,
                        BindingResult bindingResult,
                        @AuthenticationPrincipal CustomUserDetails userDetails,
                        Model model) {

                if (bindingResult.hasErrors()) {

                        addProductDetailAttributes(
                                        productId,
                                        userDetails.getId(),
                                        model);

                        return "products/detail";
                }
                try {

                        reviewService.create(
                                        productId,
                                        userDetails.getId(),
                                        reviewForm);

                } catch (ReviewAlreadyExistsException e) {

                        addProductDetailAttributes(
                                        productId,
                                        userDetails.getId(),
                                        model);

                        return "products/detail";
                }

                return "redirect:/products/" + productId;
        }

        @PostMapping("/products/{productId}/reviews/{reviewId}/edit")
        public String update(
                        @PathVariable Long productId,
                        @PathVariable Long reviewId,
                        @Valid @ModelAttribute("editReviewForm") ReviewForm reviewForm,
                        BindingResult bindingResult,
                        @AuthenticationPrincipal CustomUserDetails userDetails,
                        Model model) {

                if (bindingResult.hasErrors()) {

                        addProductDetailAttributes(
                                        productId,
                                        userDetails.getId(),
                                        model);

                        model.addAttribute("reviewForm", new ReviewForm());

                        return "products/detail";
                }

                reviewService.update(
                                reviewId,
                                userDetails.getId(),
                                reviewForm);

                return "redirect:/products/" + productId;
        }

        @PostMapping("/products/{productId}/reviews/{reviewId}/delete")
        public String delete(
                        @PathVariable Long productId,
                        @PathVariable Long reviewId,
                        @AuthenticationPrincipal CustomUserDetails userDetails) {

                reviewService.delete(
                                reviewId,
                                userDetails.getId());

                return "redirect:/products/" + productId;
        }

        private void addProductDetailAttributes(
                        Long productId,
                        Long userId,
                        Model model) {

                Product product = productService.findById(productId);

                List<Review> reviews = reviewService.findByProductId(productId);

                model.addAttribute("product", product);
                model.addAttribute("reviews", reviews);
                model.addAttribute(
                                "reviewCount",
                                reviewService.countByProductId(productId));
                model.addAttribute(
                                "averageRating",
                                reviewService.getAverageRating(productId));
                model.addAttribute(
                                "myReview",
                                reviewService.findByProductIdAndUserId(
                                                productId,
                                                userId));
        }
}