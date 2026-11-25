package com.example.ecsite.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Feature142-B: closing unpaid orders automatically (docs/feature142/unpaid-order-closure.md). Disabled by default.
 * V110 itself refuses anything younger than 60 minutes. {@code retryAfter} is the wait after a closure that was
 * released or rejected, whoever requested it.
 */
@ConfigurationProperties(prefix = "app.unpaid-order-closure")
public record UnpaidOrderClosureProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("24h") Duration after,
        @DefaultValue("5m") Duration fixedDelay,
        @DefaultValue("6h") Duration retryAfter,
        @DefaultValue("20") int batchSize) {
    private static final String PREFIX = "app.unpaid-order-closure.";

    public UnpaidOrderClosureProperties {
        duration("after", after, Duration.ofMinutes(60), Duration.ofDays(30));
        duration("fixed-delay", fixedDelay, Duration.ofSeconds(10), Duration.ofHours(24));
        duration("retry-after", retryAfter, Duration.ofMinutes(10), Duration.ofDays(7));
        if (batchSize < 1 || batchSize > 100)
            throw new IllegalArgumentException(PREFIX + "batch-sizeは1以上100以下で指定してください");
    }

    private static void duration(String name, Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException(PREFIX + name + "は" + minimum + "以上" + maximum + "以下で指定してください");
    }
}
