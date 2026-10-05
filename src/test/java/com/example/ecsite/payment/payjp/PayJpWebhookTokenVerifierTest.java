package com.example.ecsite.payment.payjp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.ecsite.config.PayJpProperties;

class PayJpWebhookTokenVerifierTest {

    private final PayJpWebhookTokenVerifier verifier =
            new PayJpWebhookTokenVerifier(
                    new PayJpProperties(
                            "pk_test",
                            "sk_test",
                            "https://api.pay.jp",
                            "wh_test_secret"));

    @Test
    void acceptsMatchingToken() {
        assertTrue(verifier.isValid("wh_test_secret"));
    }

    @Test
    void rejectsDifferentOrMissingToken() {
        assertFalse(verifier.isValid("wrong-token"));
        assertFalse(verifier.isValid(null));
    }
}
