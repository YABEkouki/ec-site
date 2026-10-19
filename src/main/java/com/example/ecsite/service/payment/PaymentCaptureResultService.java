package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.payment.CaptureResult;

@Service
public class PaymentCaptureResultService {
    private final CaptureShipmentOperations operations;
    public PaymentCaptureResultService(CaptureShipmentOperations operations) { this.operations = operations; }

    @Transactional
    public PaymentResultApplication apply(PaymentOperationSnapshot snapshot, CaptureResult result) {
        return operations.apply(snapshot, result);
    }
}
