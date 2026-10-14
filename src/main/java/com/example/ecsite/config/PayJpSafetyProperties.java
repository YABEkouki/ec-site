package com.example.ecsite.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.AssertTrue;

/** Application safeguards; the POST window is not a provider retention guarantee. */
@Validated
@ConfigurationProperties("payjp.safety")
public record PayJpSafetyProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("23h") Duration postMaxAge) {
    @AssertTrue(message = "timeouts must be at least 1ms and POST age must be positive and at most 23h")
    public boolean isValid() {
        return positiveMillis(connectTimeout) && positiveMillis(readTimeout)
                && positiveMillis(postMaxAge) && postMaxAge.compareTo(Duration.ofHours(23)) <= 0;
    }
    private boolean positiveMillis(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0
                && value.compareTo(Duration.ofDays(1)) <= 0;
    }
}
