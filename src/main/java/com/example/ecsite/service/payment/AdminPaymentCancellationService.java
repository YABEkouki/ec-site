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
    private final PaymentPostGuardService postGuard;
    private final PaymentCancellationCompletionService resultService;

    public AdminPaymentCancellationService(
            AdminPaymentCancellationStartService startService,
            PaymentGateway paymentGateway,
            PaymentCancellationCompletionService resultService, PaymentPostGuardService postGuard) {

        this.postGuard = postGuard;
        this.startService = startService;
        this.paymentGateway = paymentGateway;
        this.resultService = resultService;
    }

    public CancellationResult cancel(
            Long orderId,
            Long accountId,
            String username,
            String internalNote) {

        AdminCancellationStart decision = startService.start(
                orderId,
                accountId,
                username,
                internalNote);
        if (decision.localCancelled()) {
            return new CancellationResult(CancellationResultStatus.CANCELLED, null);
        }
        PaymentCancellationStart start = decision.paymentStart();

        CancellationResult result;
        if (start.disposition() == PaymentStartDisposition.NEW) {
            postGuard.beforeNewPost(start.snapshot());
            result = paymentGateway.cancelAuthorization(new CancellationRequest(
                    start.providerPaymentId(), start.idempotencyKey()));
        } else {
            requireProviderId(start.providerPaymentId(), orderId);
            PaymentFlowState state = paymentGateway.retrievePaymentFlow(start.providerPaymentId());
            result = new CancellationResult(state.status() == PaymentFlowStatus.CANCELED
                    ? CancellationResultStatus.CANCELLED
                    : CancellationResultStatus.PENDING, state.providerPaymentId());
            if (result.status() == CancellationResultStatus.PENDING) {
                return result;
            }
        }

        try {
        resultService.apply(orderId, start, result);
        } catch (RuntimeException failure) {
            throw new PaymentSafetyStopException(PaymentSafetyStopException.Reason.RESULT_PERSISTENCE_UNKNOWN, orderId);
        }

        return result;
    }

    private void requireProviderId(String providerId, Long orderId) {
        if (providerId == null || providerId.isBlank()) {
            throw new PaymentSafetyStopException(PaymentSafetyStopException.Reason.PROVIDER_ID_MISSING, orderId);
        }
    }
}
