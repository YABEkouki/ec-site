package com.example.ecsite.service.warehouse;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Feature132-B: disabled unless app.warehouse-shipment-import.enabled=true. Uploaded lines wait as PENDING
 * until it runs. Each pass is safe to repeat.
 */
@Component
@ConditionalOnProperty(prefix = "app.warehouse-shipment-import", name = "enabled", havingValue = "true")
public class WarehouseShipmentImportScheduler {
    private final WarehouseShipmentImportWorker worker;

    public WarehouseShipmentImportScheduler(WarehouseShipmentImportWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.warehouse-shipment-import.fixed-delay:1m}")
    public void processPending() {
        try {
            worker.runPass();
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Warehouse shipment import pass deferred", failure);
        }
    }
}
