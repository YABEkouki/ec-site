package com.example.ecsite.service;

public enum EmailVerificationResult {
    VERIFIED,
    INVALID,
    EXPIRED,
    USED,
    EMAIL_CHANGED,
    ALREADY_VERIFIED
}
