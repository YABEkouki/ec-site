package com.example.ecsite.payment.capture;

/** Non-secret, operator-verified identity. Never inferred from an API key. */
public record CaptureConnectionIdentity(String connectionId, String providerAccountRef, String mode, String apiOrigin) {
    public CaptureConnectionIdentity {
        requireIdentifier(connectionId);
        requireIdentifier(providerAccountRef);
        if (!"TEST".equals(mode) && !"LIVE".equals(mode)) {
            throw new IllegalArgumentException("Explicit Capture connection mode is required");
        }
        if (!"https://api.pay.jp".equals(apiOrigin)) {
            throw new IllegalArgumentException("Unsupported Capture API origin");
        }
    }
    private static void requireIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("Explicit non-secret Capture connection identity is required");
        }
    }
}
