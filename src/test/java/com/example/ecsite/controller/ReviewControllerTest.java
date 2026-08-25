package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;

import com.example.ecsite.form.ReviewForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.ProductService;
import com.example.ecsite.service.ReviewService;

@ExtendWith(MockitoExtension.class)
class ReviewControllerTest {

    @Mock
    private ReviewService reviewService;

    @Mock
    private ProductService productService;

    @Mock
    private Model model;

    private ReviewController controller;

    @BeforeEach
    void setUp() {

        controller = new ReviewController(
                reviewService,
                productService);
    }

    @Test
    void createUsesAuthenticatedUserIdAndRedirectsToProduct() {

        Long productId = 1L;
        Long userId = 2L;

        ReviewForm reviewForm = new ReviewForm();
        reviewForm.setRating(5);
        reviewForm.setComment("とても良い商品です");

        BindingResult bindingResult = new BeanPropertyBindingResult(
                reviewForm,
                "reviewForm");

        CustomUserDetails userDetails = new CustomUserDetails(
                userId,
                "user1",
                "password",
                true,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_USER")));

        String view = controller.create(
                productId,
                reviewForm,
                bindingResult,
                userDetails,
                model);

        assertEquals(
                "redirect:/products/1",
                view);

        verify(reviewService).create(
                productId,
                userId,
                reviewForm);
    }

    @Test
    void createDoesNotSaveWhenValidationHasErrors() {

        Long productId = 1L;
        Long userId = 2L;

        ReviewForm reviewForm = new ReviewForm();
        reviewForm.setRating(null);
        reviewForm.setComment("");

        BindingResult bindingResult = new BeanPropertyBindingResult(
                reviewForm,
                "reviewForm");

        bindingResult.rejectValue(
                "rating",
                "invalid",
                "評価を選択してください");

        CustomUserDetails userDetails = new CustomUserDetails(
                userId,
                "user1",
                "password",
                true,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_USER")));

        String view = controller.create(
                productId,
                reviewForm,
                bindingResult,
                userDetails,
                model);

        assertEquals(
                "products/detail",
                view);

        verify(reviewService, never())
                .create(
                        productId,
                        userId,
                        reviewForm);
    }

    @Test
    void updateUsesAuthenticatedUserIdAndRedirectsToProduct() {

        Long productId = 1L;
        Long reviewId = 10L;
        Long userId = 2L;

        ReviewForm reviewForm = new ReviewForm();
        reviewForm.setRating(4);
        reviewForm.setComment("更新後のコメント");

        BindingResult bindingResult = new BeanPropertyBindingResult(
                reviewForm,
                "editReviewForm");

        CustomUserDetails userDetails = new CustomUserDetails(
                userId,
                "user1",
                "password",
                true,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_USER")));

        String view = controller.update(
                productId,
                reviewId,
                reviewForm,
                bindingResult,
                userDetails,
                model);

        assertEquals(
                "redirect:/products/1",
                view);

        verify(reviewService).update(
                reviewId,
                userId,
                reviewForm);
    }

    @Test
    void updateDoesNotSaveWhenValidationHasErrors() {

        Long productId = 1L;
        Long reviewId = 10L;
        Long userId = 2L;

        ReviewForm reviewForm = new ReviewForm();
        reviewForm.setRating(null);
        reviewForm.setComment("");

        BindingResult bindingResult = new BeanPropertyBindingResult(
                reviewForm,
                "editReviewForm");

        bindingResult.rejectValue(
                "rating",
                "invalid",
                "評価を選択してください");

        CustomUserDetails userDetails = new CustomUserDetails(
                userId,
                "user1",
                "password",
                true,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_USER")));

        String view = controller.update(
                productId,
                reviewId,
                reviewForm,
                bindingResult,
                userDetails,
                model);

        assertEquals(
                "products/detail",
                view);

        verify(reviewService, never())
                .update(
                        reviewId,
                        userId,
                        reviewForm);
    }

    @Test
    void deleteUsesAuthenticatedUserIdAndRedirectsToProduct() {

        Long productId = 1L;
        Long reviewId = 10L;
        Long userId = 2L;

        CustomUserDetails userDetails = new CustomUserDetails(
                userId,
                "user1",
                "password",
                true,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_USER")));

        String view = controller.delete(
                productId,
                reviewId,
                userDetails);

        assertEquals(
                "redirect:/products/1",
                view);

        verify(reviewService).delete(
                reviewId,
                userId);
    }
}