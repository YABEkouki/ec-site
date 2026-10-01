package com.example.ecsite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payjp")
public record PayJpProperties(
        String publicKey,
        String secretKey,
        String apiBaseUrl) {
}
