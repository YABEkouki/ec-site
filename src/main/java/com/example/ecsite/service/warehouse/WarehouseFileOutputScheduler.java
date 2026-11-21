package com.example.ecsite.service.warehouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Feature137-B: disabled unless app.warehouse-file.enabled=true (WAREHOUSE_FILE_ENABLED). */
@Component
@ConditionalOnProperty(prefix = "app.warehouse-file", name = "enabled", havingValue = "true")
public class WarehouseFileOutputScheduler {
    private static final Logger log = LoggerFactory.getLogger(WarehouseFileOutputScheduler.class);
    private final WarehouseFileOutputService service;

    public WarehouseFileOutputScheduler(WarehouseFileOutputService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.warehouse-file.fixed-delay:1m}")
    public void tick() {
        try {
            service.runPass();
        } catch (RuntimeException failure) {
            log.warn("Warehouse file output pass deferred; a later pass retries. error={}", failure.getClass().getSimpleName());
        }
    }
}
