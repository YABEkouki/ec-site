package com.example.ecsite.payment;

/**
 * Authorization outcome. In the PAY.JP path, providerTransactionId on success references
 * the verified Payment Flow ID, not the ID of PAY.JP's separate Payment Transaction resource.
 */
public record AuthorizationResult(
        AuthorizationResultStatus status,
        String providerTransactionId,
        String failureCode,
        String failureMessage,
        /** Feature135: provider authorization expiry for AUTHORIZED results; null when not returned. */
        java.time.Instant expiresAt) {

    public AuthorizationResult(AuthorizationResultStatus status, String providerTransactionId,
            String failureCode, String failureMessage) {
        this(status, providerTransactionId, failureCode, failureMessage, null);
    }
}
