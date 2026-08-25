package com.example.ecsite.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.Review;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.ReviewAccessDeniedException;
import com.example.ecsite.exception.ReviewAlreadyExistsException;
import com.example.ecsite.exception.ReviewNotFoundException;
import com.example.ecsite.form.ReviewForm;
import com.example.ecsite.repository.ReviewRepository;

@Service
@Transactional
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductService productService;
    private final UserService userService;

    public ReviewService(
            ReviewRepository reviewRepository,
            ProductService productService,
            UserService userService) {

        this.reviewRepository = reviewRepository;
        this.productService = productService;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public List<Review> findByProductId(Long productId) {

        return reviewRepository
                .findByProductIdOrderByCreatedAtDesc(productId);
    }

    @Transactional(readOnly = true)
    public Optional<Review> findByProductIdAndUserId(
            Long productId,
            Long userId) {

        return reviewRepository
                .findByProductIdAndUserId(productId, userId);
    }

    public Review create(
            Long productId,
            Long userId,
            ReviewForm reviewForm) {

        Product product = productService.findById(productId);
        User user = userService.findById(userId);

        if (reviewRepository.existsByProductIdAndUserId(
                productId,
                userId)) {

            throw new ReviewAlreadyExistsException();
        }

        Review review = new Review();

        review.setProduct(product);
        review.setUser(user);
        review.setRating(reviewForm.getRating());
        review.setComment(reviewForm.getComment());

        return reviewRepository.save(review);
    }

    @Transactional(readOnly = true)
    public long countByProductId(Long productId) {

        return reviewRepository.countByProductId(productId);
    }

    @Transactional(readOnly = true)
    public double getAverageRating(Long productId) {

        Double average = reviewRepository
                .findAverageRatingByProductId(productId);

        return average != null ? average : 0.0;
    }

    public Review update(
            Long reviewId,
            Long userId,
            ReviewForm reviewForm) {

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));

        if (!review.getUser().getId().equals(userId)) {
            throw new ReviewAccessDeniedException();
        }

        review.setRating(reviewForm.getRating());
        review.setComment(reviewForm.getComment());

        return review;
    }

    public void delete(
            Long reviewId,
            Long userId) {

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));

        if (!review.getUser().getId().equals(userId)) {
            throw new ReviewAccessDeniedException();
        }

        reviewRepository.delete(review);
    }
}