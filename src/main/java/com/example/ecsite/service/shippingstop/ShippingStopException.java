package com.example.ecsite.service.shippingstop;

/** Feature146: a shipping stop refused before anything was written; the message is shown to the administrator. */
public class ShippingStopException extends RuntimeException {
    public ShippingStopException(String message) {
        super(message);
    }
}
