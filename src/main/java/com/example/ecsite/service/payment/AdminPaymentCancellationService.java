package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;

import com.example.ecsite.payment.CancellationRequest;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.PaymentGateway;

@Service
public class AdminPaymentCancellationService {

    private final AdminPaymentCancellationStartService startService;
    private final PaymentGateway paymentGateway;
    private final AdminPaymentCancellationResultService resultService;

    public AdminPaymentCancellationService(
            AdminPaymentCancellationStartService startService,
            PaymentGateway paymentGateway,
            AdminPaymentCancellationResultService resultService) {

        this.startService = startService;
        this.paymentGateway = paymentGateway;
        this.resultService = resultService;
    }

    public CancellationResult cancel(
            Long orderId,
            Long accountId,
            String username,
            String internalNote) {

        PaymentCancellationStart start = startService.start(
                orderId,
                accountId,
                username,
                internalNote);

        CancellationResult result = paymentGateway.cancelAuthorization(
                new CancellationRequest(
                        start.providerPaymentId(),
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
