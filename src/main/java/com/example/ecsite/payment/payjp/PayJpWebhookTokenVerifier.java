package com.example.ecsite.payment.payjp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;

import com.example.ecsite.config.PayJpProperties;

@Component
public class PayJpWebhookTokenVerifier {

    private final byte[] expectedToken;

    public PayJpWebhookTokenVerifier(PayJpProperties properties) {

        this.expectedToken = properties.webhookToken()
                .getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(String actualToken) {

        if (actualToken == null) {
            return false;
        }

        return MessageDigest.isEqual(
                expectedToken,
                actualToken.getBytes(StandardCharsets.UTF_8));
    }
}
