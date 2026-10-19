package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short writable lock transactions; their commits precede any external GET. */
@Service
public class AdminCaptureRequeryPreparationService {
    private final CaptureShipmentOperations operations;
    public AdminCaptureRequeryPreparationService(CaptureShipmentOperations operations) { this.operations = operations; }

    @Transactional
    public PaymentCaptureStart prepare(Long orderId) { return operations.prepareRequery(orderId); }

    @Transactional
    public PaymentStartDisposition latest(PaymentOperationSnapshot snapshot) {
        return operations.confirmRequery(snapshot).disposition();
    }
}
