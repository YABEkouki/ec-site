package com.example.ecsite.service.warehouse;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Feature131 C-2: disabled unless app.warehouse-export.enabled=true. Every run is idempotent: before 14:05, or once
 * the day's batch is sealed, it does nothing. Enabling it without a start date fails application startup.
 */
@Component
@ConditionalOnProperty(prefix = "app.warehouse-export", name = "enabled", havingValue = "true")
public class WarehouseExportScheduler {
    private final WarehouseExportRunner runner;

    public WarehouseExportScheduler(WarehouseExportRunner runner) {
        if (runner.startDate() == null) {
            throw new IllegalStateException("app.warehouse-export.start-date is required when the warehouse export is enabled");
        }
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${app.warehouse-export.fixed-delay:1m}")
    public void exportDue() {
        try {
            runner.runDue();
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Warehouse export run deferred", failure);
        }
    }
}
