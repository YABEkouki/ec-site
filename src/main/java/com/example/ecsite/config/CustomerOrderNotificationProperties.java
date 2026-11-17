package com.example.ecsite.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Feature133-B: customer order notification delivery. Disabled by default; while disabled, notifications are still
 * recorded (V91) but never sent, and on enabling those older than {@code maxAge} expire instead of being sent.
 */
@ConfigurationProperties(prefix = "app.customer-notification")
public record CustomerOrderNotificationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("30s") Duration fixedDelay,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5m") Duration retryDelay,
        @DefaultValue("5m") Duration claimLease,
        @DefaultValue("24h") Duration maxAge,
        @DefaultValue Smtp smtp) {
    private static final String PREFIX = "app.customer-notification.";

    public CustomerOrderNotificationProperties {
        duration("fixed-delay", fixedDelay, Duration.ofSeconds(5), Duration.ofHours(1));
        duration("retry-delay", retryDelay, Duration.ofMinutes(1), Duration.ofHours(24));
        duration("claim-lease", claimLease, Duration.ofMinutes(1), Duration.ofMinutes(30));
        duration("max-age", maxAge, Duration.ofHours(1), Duration.ofDays(7));
        if (batchSize < 1 || batchSize > 100) invalid("batch-size", "1以上100以下で指定してください");
        if (smtp == null) invalid("smtp", "設定が必要です");
        // A claim must outlive the worst-case SMTP exchange so a live send is never recovered as lost.
        if (claimLease.compareTo(smtp.worstCase().plusMinutes(1)) < 0)
            invalid("claim-lease", "SMTPのタイムアウト合計より1分以上長く指定してください");
    }

    public record Smtp(
            @DefaultValue("5s") Duration connectionTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("10s") Duration writeTimeout) {
        public Smtp {
            duration("smtp.connection-timeout", connectionTimeout, Duration.ofSeconds(1), Duration.ofMinutes(1));
            duration("smtp.read-timeout", readTimeout, Duration.ofSeconds(1), Duration.ofMinutes(2));
            duration("smtp.write-timeout", writeTimeout, Duration.ofSeconds(1), Duration.ofMinutes(2));
        }

        Duration worstCase() {
            return connectionTimeout.plus(readTimeout.multipliedBy(4)).plus(writeTimeout);
        }
    }

    private static void duration(String name, Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            invalid(name, minimum + "以上" + maximum + "以下で指定してください");
    }

    private static void invalid(String name, String message) {
        throw new IllegalArgumentException(PREFIX + name + "は" + message);
    }
}
