package com.example.ecsite.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "app.payment.discrepancy-audit.monitoring")
public record PaymentDiscrepancyAuditMonitoringProperties(
        @NotNull @DefaultValue("15m") Duration runningThreshold,
        @NotNull @DefaultValue("10m") Duration delayGrace,
        @Min(value = 1, message = "app.payment.discrepancy-audit.monitoring.consecutive-failuresは1以上100以下で指定してください。")
        @Max(value = 100, message = "app.payment.discrepancy-audit.monitoring.consecutive-failuresは1以上100以下で指定してください。")
        @DefaultValue("3") int consecutiveFailures,
        @NotNull @DefaultValue("24h") Duration longUnhandledAge) {
    private static final Duration MAX_EXECUTION_THRESHOLD = Duration.ofDays(7);
    private static final Duration MAX_UNHANDLED_AGE = Duration.ofDays(365);

    public PaymentDiscrepancyAuditMonitoringProperties {
        validateDuration("running-threshold", runningThreshold, MAX_EXECUTION_THRESHOLD, false, "0より大きく7日以下");
        validateDuration("delay-grace", delayGrace, MAX_EXECUTION_THRESHOLD, true, "0以上7日以下");
        validateDuration("long-unhandled-age", longUnhandledAge, MAX_UNHANDLED_AGE, false, "0より大きく365日以下");
        if (consecutiveFailures < 1 || consecutiveFailures > 100) {
            throw new IllegalArgumentException(
                "app.payment.discrepancy-audit.monitoring.consecutive-failuresは1以上100以下で指定してください。");
        }
    }

    private static void validateDuration(String setting, Duration value, Duration maximum,
            boolean allowZero, String range) {
        if (value == null || value.isNegative() || (!allowZero && value.isZero()) || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("app.payment.discrepancy-audit.monitoring." + setting
                + "は" + range + "で指定してください。");
        }
    }
}
