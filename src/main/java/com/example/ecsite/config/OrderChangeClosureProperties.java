package com.example.ecsite.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Feature143-B: closing unfinished order changes automatically (docs/feature143/unfinished-change-closure.md, chapter 15).
 * Disabled by default. {@code after} counts from the change's automatic_until, before which V111 refuses any closure.
 * {@code retryAfter} is the wait after a closure that was released or rejected, whoever requested it.
 */
@ConfigurationProperties(prefix = "app.order-change-closure")
public record OrderChangeClosureProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("60m") Duration after,
        @DefaultValue("5m") Duration fixedDelay,
        @DefaultValue("6h") Duration retryAfter,
        @DefaultValue("20") int batchSize) {
    private static final String PREFIX = "app.order-change-closure.";

    public OrderChangeClosureProperties {
        duration("after", after, Duration.ofMinutes(10), Duration.ofDays(7));
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
