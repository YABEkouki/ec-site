package com.example.ecsite.exception;

public class ReviewNotFoundException extends RuntimeException {

    public ReviewNotFoundException(Long reviewId) {
        super("レビューが見つかりません: " + reviewId);
    }
}