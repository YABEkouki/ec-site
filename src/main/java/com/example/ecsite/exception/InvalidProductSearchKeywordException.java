package com.example.ecsite.exception;

public class InvalidProductSearchKeywordException extends RuntimeException {

    public InvalidProductSearchKeywordException(String message) {
        super(message);
    }
}
