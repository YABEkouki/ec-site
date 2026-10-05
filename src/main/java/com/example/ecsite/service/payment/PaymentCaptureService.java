package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;

import com.example.ecsite.payment.CaptureRequest;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.PaymentGateway;

@Service
public class PaymentCaptureService {

    private final PaymentCaptureStartService startService;
    private final PaymentGateway paymentGateway;
    private final PaymentCaptureResultService resultService;

    public PaymentCaptureService(
            PaymentCaptureStartService startService,
            PaymentGateway paymentGateway,
            PaymentCaptureResultService resultService) {

        this.startService = startService;
        this.paymentGateway = paymentGateway;
        this.resultService = resultService;
    }

    public CaptureResult captureForShipment(
            Long orderId,
            Long accountId,
            String username,
            String internalNote) {

        PaymentCaptureStart start = startService.start(
                orderId,
                accountId,
                username,
                internalNote);

        CaptureResult result = paymentGateway.capture(
                new CaptureRequest(
                        start.providerPaymentId(),
                        start.amount(),
                        start.idempotencyKey()));

        resultService.apply(
                orderId,
                start.paymentId(),
                accountId,
                username,
                internalNote,
                result);

        return result;
    }
}
