package com.example.ecsite.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "app.payment.discrepancy-audit")
public record PaymentDiscrepancyAuditProperties(
        boolean enabled,
        @NotNull Duration fixedDelay,
        @Min(1) int batchSize) {
}
