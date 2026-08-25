package com.example.ecsite.exception;

public class FavoriteAlreadyExistsException extends RuntimeException {

    public FavoriteAlreadyExistsException() {
        super("この商品はすでにお気に入りに登録されています。");
    }
}