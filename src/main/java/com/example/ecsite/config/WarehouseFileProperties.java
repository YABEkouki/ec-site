package com.example.ecsite.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Feature137-B: writing the warehouse files to the folder the FTP job collects from. Disabled by default; while
 * disabled, outputs are still recorded (V102, PENDING) and are written oldest first once enabled.
 * {@code outputDir} is the collected folder; {@code workDir} holds the temporary files and must be on the same
 * file system (the files are moved atomically) but outside the collected folder.
 */
@ConfigurationProperties(prefix = "app.warehouse-file")
public record WarehouseFileProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String outputDir,
        @DefaultValue("") String workDir,
        @DefaultValue("1m") Duration fixedDelay,
        @DefaultValue("5m") Duration retryDelay,
        @DefaultValue("12") int maxAttempts,
        @DefaultValue("5m") Duration claimLease,
        @DefaultValue("20") int batchSize) {
    private static final String PREFIX = "app.warehouse-file.";

    public WarehouseFileProperties {
        outputDir = outputDir == null ? "" : outputDir.strip();
        workDir = workDir == null ? "" : workDir.strip();
        duration("fixed-delay", fixedDelay, Duration.ofSeconds(5), Duration.ofHours(1));
        duration("retry-delay", retryDelay, Duration.ofSeconds(10), Duration.ofHours(24));
        duration("claim-lease", claimLease, Duration.ofMinutes(1), Duration.ofMinutes(30));
        if (maxAttempts < 1 || maxAttempts > 100) invalid("max-attempts", "1以上100以下で指定してください");
        if (batchSize < 1 || batchSize > 100) invalid("batch-size", "1以上100以下で指定してください");
        if (enabled && (outputDir.isEmpty() || workDir.isEmpty())) {
            invalid("output-dir / work-dir", "有効にするときは両方とも指定してください（WAREHOUSE_FILE_OUTPUT_DIR / WAREHOUSE_FILE_WORK_DIR）");
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
