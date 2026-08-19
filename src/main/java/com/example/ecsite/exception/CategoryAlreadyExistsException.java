package com.example.ecsite.exception;

public class CategoryAlreadyExistsException
        extends RuntimeException {

    public CategoryAlreadyExistsException(String name) {
        super("カテゴリ名は既に使用されています: " + name);
    }

    public CategoryAlreadyExistsException(
            String name,
            Throwable cause) {

        super(
                "カテゴリ名は既に使用されています: "
                        + name,
                cause);
    }
}