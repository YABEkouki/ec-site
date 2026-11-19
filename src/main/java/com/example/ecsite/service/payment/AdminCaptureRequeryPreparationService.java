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

    /** Feature135-B2: the capture was settled as FAILED because PAY.JP canceled the Flow. */
    @Transactional(readOnly = true)
    public boolean failedByCanceledFlow(PaymentOperationSnapshot snapshot) {
        return operations.failedByCanceledFlow(snapshot);
    }

    @Transactional
    public PaymentStartDisposition latest(PaymentOperationSnapshot snapshot) {
        return operations.confirmRequery(snapshot).disposition();
    }
}
