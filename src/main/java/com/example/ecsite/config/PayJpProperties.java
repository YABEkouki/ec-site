package com.example.ecsite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "payjp")
public record PayJpProperties(
        @NotBlank String publicKey,
        @NotBlank String secretKey,
        @NotBlank String apiBaseUrl) {
}
