package com.example.ecsite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import com.example.ecsite.payment.capture.CaptureConnectionIdentity;

/** Required for NEW Capture and its initial POST; GET-only continuations do not read these properties.
 * Operators must verify account/mode correspondence; this class cannot prove API-key ownership.
 */
@ConfigurationProperties("payjp.capture-connection")
public record CaptureConnectionProperties(String connectionId, String providerAccountRef, String mode, String apiOrigin) {
    public CaptureConnectionIdentity requireIdentity(PayJpProperties active) {
        try {
            var identity = new CaptureConnectionIdentity(connectionId, providerAccountRef, mode, apiOrigin);
            String suffix = "TEST".equals(mode) ? "test_" : "live_";
            if (active == null || !apiOrigin.equals(active.apiBaseUrl())
                    || active.secretKey() == null || !active.secretKey().startsWith("sk_" + suffix)
                    || active.publicKey() == null || !active.publicKey().startsWith("pk_" + suffix)
                    || active.secretKey().length() <= ("sk_" + suffix).length()
                    || active.publicKey().length() <= ("pk_" + suffix).length()) {
                throw new IllegalArgumentException("Capture connection mode or origin does not match active configuration");
            }
            return identity;
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Capture connection identity is not configured or is invalid", failure);
        }
    }
}
