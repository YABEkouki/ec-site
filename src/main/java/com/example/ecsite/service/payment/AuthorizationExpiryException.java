package com.example.ecsite.service.payment;

/**
 * Feature135: the card authorization has expired, or expires too soon for the requested shipping step.
 * Thrown before any mutation; the caller's transaction rolls back.
 */
public class AuthorizationExpiryException extends IllegalStateException {
    public AuthorizationExpiryException(String message) {
        super(message);
    }
}
