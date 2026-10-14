package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.payment.CaptureRequest;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;

@Service
@Transactional(propagation = Propagation.NEVER)
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

        CaptureResult result;
        if (start.disposition() == PaymentStartDisposition.NEW) {
            result = paymentGateway.capture(new CaptureRequest(
                    start.providerPaymentId(), start.amount(), start.idempotencyKey()));
        } else {
            requireProviderId(start.providerPaymentId());
            PaymentFlowState state = paymentGateway.retrievePaymentFlow(start.providerPaymentId());
            result = new CaptureResult(state.status() == PaymentFlowStatus.SUCCEEDED
                    ? CaptureResultStatus.CAPTURED
                    : CaptureResultStatus.PENDING,
                    state.providerPaymentId(), null, null);
            if (result.status() == CaptureResultStatus.PENDING) {
                return result;
            }
        }

        resultService.apply(
                start.snapshot(), result).requireAccepted();

        return result;
    }

    private void requireProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalStateException("決済IDが未設定のため、決済操作の再送を停止しています。");
        }
    }
}
