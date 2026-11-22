package com.example.ecsite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature139: the PAY.JP customer id of a user is {@code idPrefix + users.id}; nothing maps users to customers in
 * the database. Change the prefix whenever the database is recreated, because user ids are reused. Without a valid
 * prefix saved cards are unavailable (startup is not stopped).
 */
@ConfigurationProperties("payjp.customer")
public record PayJpCustomerProperties(String idPrefix) {
    private static final String PREFIX = "[A-Za-z0-9_-]{0,39}-";

    public boolean enabled() {
        return idPrefix != null && idPrefix.matches(PREFIX);
    }

    public String customerId(long userId) {
        if (!enabled()) throw new IllegalStateException("Saved cards are not configured");
        if (userId <= 0) throw new IllegalArgumentException("Positive user ID required");
        return idPrefix + userId;
    }
}
