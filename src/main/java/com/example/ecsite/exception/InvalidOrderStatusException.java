package com.example.ecsite.exception;

public class InvalidOrderStatusException
        extends RuntimeException {

    public InvalidOrderStatusException(String message) {
        super(message);
    }
}