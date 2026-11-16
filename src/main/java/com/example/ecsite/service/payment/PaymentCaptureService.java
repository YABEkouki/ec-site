package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.service.warehouse.WarehouseShipmentActor;

@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentCaptureService {

    private final PaymentCaptureStartService startService;
    private final PaymentGateway paymentGateway;
    private final CaptureInitialPostPreparationService preparation;
    private final Clock clock;
    private final PaymentCaptureResultService resultService;

    public PaymentCaptureService(
            PaymentCaptureStartService startService,
            PaymentGateway paymentGateway,
            PaymentCaptureResultService resultService, CaptureInitialPostPreparationService preparation, Clock clock) {

        this.preparation = preparation; this.clock = clock;
        this.startService = startService;
        this.paymentGateway = paymentGateway;
        this.resultService = resultService;
    }

    public CaptureResult captureForShipment(
            Long orderId,
            Long accountId,
            String username,
            String internalNote) {

        return capture(orderId, startService.start(
                orderId,
                accountId,
                username,
                internalNote));
    }

    /**
     * Feature132-A: the same shipment capture, started as the system (warehouse shipment import). Unknown
     * results stay SHIPPED with a PENDING capture and converge through reconciliation, as for an administrator.
     */
    public CaptureResult captureForWarehouseImport(Long orderId, WarehouseShipmentActor actor) {
        return capture(orderId, startService.startAsWarehouseImport(orderId, actor));
    }

    private CaptureResult capture(Long orderId, PaymentCaptureStart start) {
        if (start.disposition() == PaymentStartDisposition.ALREADY_APPLIED) {
            return new CaptureResult(CaptureResultStatus.CAPTURED, start.providerPaymentId(), null, null);
        }
        CaptureResult result;
        if (start.disposition() == PaymentStartDisposition.NEW) {
            var prepared = preparation.prepare(start.initialPost());
            if (prepared instanceof CaptureInitialPostPreparationService.AlreadyApplied)
                return new CaptureResult(CaptureResultStatus.CAPTURED, start.providerPaymentId(), null, null);
            var ready = (CaptureInitialPostPreparationService.Ready) prepared;
            ready.requireWithinWindow(clock.instant());
            result = paymentGateway.capture(ready.request());
        } else {
            requireProviderId(start.providerPaymentId(), orderId);
            PaymentFlowState state = paymentGateway.retrievePaymentFlow(start.providerPaymentId());
            result = new CaptureResult(state.status() == PaymentFlowStatus.SUCCEEDED
                    ? CaptureResultStatus.CAPTURED
                    : CaptureResultStatus.PENDING,
                    state.providerPaymentId(), null, null);
            if (result.status() == CaptureResultStatus.PENDING) {
                return result;
            }
        }

        try {
        resultService.apply(
                start.snapshot(), result).requireAccepted();
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
