package com.example.ecsite.exception;

public class ReviewAlreadyExistsException extends RuntimeException {

    public ReviewAlreadyExistsException() {
        super("この商品にはすでにレビューを投稿しています");
    }
}