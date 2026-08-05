package com.example.ecsite.exception;

public class OrderNotFoundException
        extends RuntimeException {

    public OrderNotFoundException(Long id) {
        super("注文ID " + id + " は存在しません。");
    }
}