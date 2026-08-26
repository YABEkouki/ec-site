package com.example.ecsite.exception;

public class ShippingAddressNotFoundException
        extends RuntimeException {

    public ShippingAddressNotFoundException(Long id) {
        super("配送先ID " + id + " は存在しません。");
    }
}