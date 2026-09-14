package com.example.ecsite.exception;

public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(Long id) {
        super("顧客ID " + id + " は存在しません。");
    }
}
