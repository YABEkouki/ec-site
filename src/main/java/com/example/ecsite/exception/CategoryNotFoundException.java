package com.example.ecsite.exception;

public class CategoryNotFoundException
        extends RuntimeException {

    public CategoryNotFoundException(Long id) {
        super("カテゴリID " + id + " は存在しません。");
    }
}