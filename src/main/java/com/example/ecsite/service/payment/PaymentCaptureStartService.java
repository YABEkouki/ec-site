package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentCaptureStartService {
    private final CaptureShipmentOperations operations;
    public PaymentCaptureStartService(CaptureShipmentOperations operations) { this.operations = operations; }

    @Transactional
    public PaymentCaptureStart start(Long orderId, Long accountId, String username, String internalNote) {
        return operations.start(orderId, accountId, username, internalNote);
    }

    @Transactional
    public PaymentCaptureStart startAsWarehouseImport(Long orderId, com.example.ecsite.service.warehouse.WarehouseShipmentActor actor) {
        return operations.startAsWarehouseImport(orderId, actor);
    }
}
