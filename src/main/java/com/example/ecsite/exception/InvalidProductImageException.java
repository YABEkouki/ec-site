package com.example.ecsite.exception;

public class InvalidProductImageException extends RuntimeException {

    public InvalidProductImageException(String message) {
        super(message);
    }
}