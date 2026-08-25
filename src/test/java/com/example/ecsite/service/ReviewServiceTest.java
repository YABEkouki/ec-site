package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.Review;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.ReviewAccessDeniedException;
import com.example.ecsite.exception.ReviewAlreadyExistsException;
import com.example.ecsite.exception.ReviewNotFoundException;
import com.example.ecsite.form.ReviewForm;
import com.example.ecsite.repository.ReviewRepository;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ProductService productService;

    @Mock
    private UserService userService;

    @Test
    void createSavesReviewForProductAndUser() {

        Long productId = 1L;
        Long userId = 2L;

        Product product = new Product();
        User user = new User();

        ReviewForm form = new ReviewForm();
        form.setRating(5);
        form.setComment("とても良い商品です");

        when(productService.findById(productId))
                .thenReturn(product);

        when(userService.findById(userId))
                .thenReturn(user);

        when(reviewRepository.existsByProductIdAndUserId(
                productId,
                userId))
                .thenReturn(false);

        when(reviewRepository.save(any(Review.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        Review result = reviewService.create(
                productId,
                userId,
                form);

        assertSame(product, result.getProduct());
        assertSame(user, result.getUser());

        verify(reviewRepository)
                .save(any(Review.class));
    }

    @Test
    void createRejectsDuplicateReview() {

        Long productId = 1L;
        Long userId = 2L;

        Product product = new Product();
        User user = new User();

        ReviewForm form = new ReviewForm();
        form.setRating(5);
        form.setComment("レビュー");

        when(productService.findById(productId))
                .thenReturn(product);

        when(userService.findById(userId))
                .thenReturn(user);

        when(reviewRepository.existsByProductIdAndUserId(
                productId,
                userId))
                .thenReturn(true);

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        assertThrows(
                ReviewAlreadyExistsException.class,
                () -> reviewService.create(
                        productId,
                        userId,
                        form));

        verify(reviewRepository, never())
                .save(any(Review.class));
    }

    @Test
    void updateChangesOwnReview() {

        Long reviewId = 1L;
        Long userId = 2L;

        User user = mock(User.class);
        Review review = mock(Review.class);

        ReviewForm form = new ReviewForm();
        form.setRating(4);
        form.setComment("更新後のコメント");

        when(reviewRepository.findById(reviewId))
                .thenReturn(java.util.Optional.of(review));

        when(review.getUser())
                .thenReturn(user);

        when(user.getId())
                .thenReturn(userId);

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        Review result = reviewService.update(
                reviewId,
                userId,
                form);

        assertSame(review, result);

        verify(review)
                .setRating(4);

        verify(review)
                .setComment("更新後のコメント");
    }

    @Test
    void updateRejectsAnotherUsersReview() {

        Long reviewId = 1L;
        Long loginUserId = 2L;
        Long reviewOwnerId = 3L;

        User owner = mock(User.class);
        Review review = mock(Review.class);

        ReviewForm form = new ReviewForm();
        form.setRating(1);
        form.setComment("書き換え");

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(review));

        when(review.getUser())
                .thenReturn(owner);

        when(owner.getId())
                .thenReturn(reviewOwnerId);

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        assertThrows(
                ReviewAccessDeniedException.class,
                () -> reviewService.update(
                        reviewId,
                        loginUserId,
                        form));

        verify(review, never())
                .setRating(any());

        verify(review, never())
                .setComment(any());
    }

    @Test
    void deleteDeletesOwnReview() {

        Long reviewId = 1L;
        Long userId = 2L;

        User user = mock(User.class);
        Review review = mock(Review.class);

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(review));

        when(review.getUser())
                .thenReturn(user);

        when(user.getId())
                .thenReturn(userId);

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        reviewService.delete(
                reviewId,
                userId);

        verify(reviewRepository)
                .delete(review);
    }

    @Test
    void deleteRejectsAnotherUsersReview() {

        Long reviewId = 1L;
        Long loginUserId = 2L;
        Long reviewOwnerId = 3L;

        User owner = mock(User.class);
        Review review = mock(Review.class);

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.of(review));

        when(review.getUser())
                .thenReturn(owner);

        when(owner.getId())
                .thenReturn(reviewOwnerId);

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        assertThrows(
                ReviewAccessDeniedException.class,
                () -> reviewService.delete(
                        reviewId,
                        loginUserId));

        verify(reviewRepository, never())
                .delete(any(Review.class));
    }

    @Test
    void updateRejectsNonexistentReview() {

        Long reviewId = 999L;
        Long userId = 2L;

        ReviewForm form = new ReviewForm();
        form.setRating(4);
        form.setComment("更新");

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.empty());

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        assertThrows(
                ReviewNotFoundException.class,
                () -> reviewService.update(
                        reviewId,
                        userId,
                        form));

        verify(reviewRepository)
                .findById(reviewId);
    }

    @Test
    void deleteRejectsNonexistentReview() {

        Long reviewId = 999L;
        Long userId = 2L;

        when(reviewRepository.findById(reviewId))
                .thenReturn(Optional.empty());

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        assertThrows(
                ReviewNotFoundException.class,
                () -> reviewService.delete(
                        reviewId,
                        userId));

        verify(reviewRepository)
                .findById(reviewId);

        verify(reviewRepository, never())
                .delete(any(Review.class));
    }

    @Test
    void findByProductIdReturnsReviews() {

        Long productId = 1L;

        Review review1 = new Review();
        Review review2 = new Review();

        List<Review> expected = List.of(review1, review2);

        when(reviewRepository
                .findByProductIdOrderByCreatedAtDesc(productId))
                .thenReturn(expected);

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        List<Review> actual = reviewService.findByProductId(productId);

        assertSame(expected, actual);

        verify(reviewRepository)
                .findByProductIdOrderByCreatedAtDesc(productId);
    }

    @Test
    void countByProductIdReturnsReviewCount() {

        Long productId = 1L;

        when(reviewRepository.countByProductId(productId))
                .thenReturn(3L);

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        long result = reviewService.countByProductId(productId);

        assertEquals(3L, result);

        verify(reviewRepository)
                .countByProductId(productId);
    }

    @Test
    void getAverageRatingReturnsRepositoryAverage() {

        Long productId = 1L;

        when(reviewRepository
                .findAverageRatingByProductId(productId))
                .thenReturn(4.5);

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        double result = reviewService.getAverageRating(productId);

        assertEquals(4.5, result);

        verify(reviewRepository)
                .findAverageRatingByProductId(productId);
    }

    @Test
    void getAverageRatingReturnsZeroWhenNoReviewsExist() {

        Long productId = 1L;

        when(reviewRepository
                .findAverageRatingByProductId(productId))
                .thenReturn(null);

        ReviewService reviewService = new ReviewService(
                reviewRepository,
                productService,
                userService);

        double result = reviewService.getAverageRating(productId);

        assertEquals(0.0, result);

        verify(reviewRepository)
                .findAverageRatingByProductId(productId);
    }

}