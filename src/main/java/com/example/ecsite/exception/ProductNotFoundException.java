package com.example.ecsite.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long id) {
        super("商品ID " + id + " は存在しません。");
    }

}
