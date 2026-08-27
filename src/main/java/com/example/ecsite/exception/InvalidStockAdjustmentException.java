package com.example.ecsite.exception;

public class InvalidStockAdjustmentException extends RuntimeException {

    public InvalidStockAdjustmentException(String message) {
        super(message);
    }
}