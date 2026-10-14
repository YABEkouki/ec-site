package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.payment.CancellationRequest;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;

@Service
@Transactional(propagation = Propagation.NEVER)
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

        CancellationResult result;
        if (start.disposition() == PaymentStartDisposition.NEW) {
            result = paymentGateway.cancelAuthorization(new CancellationRequest(
                    start.providerPaymentId(), start.idempotencyKey()));
        } else {
            requireProviderId(start.providerPaymentId());
            PaymentFlowState state = paymentGateway.retrievePaymentFlow(start.providerPaymentId());
            result = new CancellationResult(state.status() == PaymentFlowStatus.CANCELED
                    ? CancellationResultStatus.CANCELLED
                    : CancellationResultStatus.PENDING, state.providerPaymentId());
            if (result.status() == CancellationResultStatus.PENDING) {
                return result;
            }
        }

        resultService.apply(
                orderId,
                start.paymentId(),
                accountId,
                username,
                internalNote,
                result);

        return result;
    }

    private void requireProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalStateException("決済IDが未設定のため、決済操作の再送を停止しています。");
        }
    }
}
