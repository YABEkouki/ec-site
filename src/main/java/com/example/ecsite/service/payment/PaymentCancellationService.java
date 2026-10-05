package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;

import com.example.ecsite.payment.CancellationRequest;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.PaymentGateway;

@Service
public class PaymentCancellationService {

    private final PaymentCancellationStartService startService;
    private final PaymentGateway paymentGateway;
    private final PaymentCancellationResultService resultService;

    public PaymentCancellationService(
            PaymentCancellationStartService startService,
            PaymentGateway paymentGateway,
            PaymentCancellationResultService resultService) {

        this.startService = startService;
        this.paymentGateway = paymentGateway;
        this.resultService = resultService;
    }

    public CancellationResult cancelForUser(
            Long orderId,
            Long userId,
            String username) {

        PaymentCancellationStart start = startService.start(
                orderId,
                userId,
                username);

        CancellationResult result = paymentGateway.cancelAuthorization(
                new CancellationRequest(
                        start.providerPaymentId(),
                        start.idempotencyKey()));

        resultService.apply(
                orderId,
                start.paymentId(),
                userId,
                username,
                result);

        return result;
    }
}
