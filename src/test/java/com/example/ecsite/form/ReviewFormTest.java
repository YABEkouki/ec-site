package com.example.ecsite.form;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ReviewFormTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void validReviewHasNoValidationErrors() {

        ReviewForm form = new ReviewForm();
        form.setRating(5);
        form.setComment("とても良い商品です");

        Set<ConstraintViolation<ReviewForm>> violations = validator.validate(form);

        assertTrue(violations.isEmpty());
    }

    @Test
    void nullRatingHasValidationError() {

        ReviewForm form = new ReviewForm();
        form.setRating(null);
        form.setComment("レビューコメント");

        Set<ConstraintViolation<ReviewForm>> violations = validator.validate(form);

        assertTrue(
                violations.stream()
                        .anyMatch(v -> v.getPropertyPath()
                                .toString()
                                .equals("rating")));
    }

    @Test
    void ratingBelowOneHasValidationError() {

        ReviewForm form = new ReviewForm();
        form.setRating(0);
        form.setComment("レビューコメント");

        Set<ConstraintViolation<ReviewForm>> violations = validator.validate(form);

        assertTrue(
                violations.stream()
                        .anyMatch(v -> v.getPropertyPath()
                                .toString()
                                .equals("rating")));
    }

    @Test
    void ratingAboveFiveHasValidationError() {

        ReviewForm form = new ReviewForm();
        form.setRating(6);
        form.setComment("レビューコメント");

        Set<ConstraintViolation<ReviewForm>> violations = validator.validate(form);

        assertTrue(
                violations.stream()
                        .anyMatch(v -> v.getPropertyPath()
                                .toString()
                                .equals("rating")));
    }

    @Test
    void blankCommentHasValidationError() {

        ReviewForm form = new ReviewForm();
        form.setRating(5);
        form.setComment("");

        Set<ConstraintViolation<ReviewForm>> violations = validator.validate(form);

        assertTrue(
                violations.stream()
                        .anyMatch(v -> v.getPropertyPath()
                                .toString()
                                .equals("comment")));
    }

    @Test
    void whitespaceOnlyCommentHasValidationError() {

        ReviewForm form = new ReviewForm();
        form.setRating(5);
        form.setComment("   ");

        Set<ConstraintViolation<ReviewForm>> violations = validator.validate(form);

        assertTrue(
                violations.stream()
                        .anyMatch(v -> v.getPropertyPath()
                                .toString()
                                .equals("comment")));
    }

    @Test
    void commentOver1000CharactersHasValidationError() {

        ReviewForm form = new ReviewForm();
        form.setRating(5);
        form.setComment("a".repeat(1001));

        Set<ConstraintViolation<ReviewForm>> violations = validator.validate(form);

        assertTrue(
                violations.stream()
                        .anyMatch(v -> v.getPropertyPath()
                                .toString()
                                .equals("comment")));
    }

    @Test
    void commentWithExactly1000CharactersIsValid() {

        ReviewForm form = new ReviewForm();
        form.setRating(5);
        form.setComment("a".repeat(1000));

        Set<ConstraintViolation<ReviewForm>> violations = validator.validate(form);

        assertTrue(violations.isEmpty());
    }

}