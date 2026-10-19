package com.example.ecsite.service.payment;

import com.example.ecsite.payment.PaymentGatewayException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** GET-only orchestration. Has no reference to Gateway POST or the shipment-start facade. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class AdminCaptureRequeryService {
    public enum Result { CONFIRMED, WAITING }
    private final AdminCaptureRequeryPreparationService preparation;
    private final PaymentOperationSyncService synchronization;
    public AdminCaptureRequeryService(AdminCaptureRequeryPreparationService preparation, PaymentOperationSyncService synchronization) {
        this.preparation = preparation; this.synchronization = synchronization;
    }
    public Result requery(Long orderId) {
        PaymentCaptureStart target = preparation.prepare(orderId);
        if (target.disposition() == PaymentStartDisposition.ALREADY_APPLIED) return Result.CONFIRMED;
        if (target.disposition() != PaymentStartDisposition.REUSED) throw new CaptureRequeryRejectedException();
        PaymentSynchronizationOutcome outcome;
        try {
            outcome = synchronization.synchronize(target.snapshot());
        } catch (PaymentGatewayException failure) {
            // A concurrent webhook/reconciliation may have committed success while GET timed out.
            if (preparation.latest(target.snapshot()) == PaymentStartDisposition.ALREADY_APPLIED) return Result.CONFIRMED;
            throw failure;
        }
        PaymentStartDisposition latest = preparation.latest(target.snapshot());
        if (latest == PaymentStartDisposition.ALREADY_APPLIED) return Result.CONFIRMED;
        if (latest != PaymentStartDisposition.REUSED || outcome != PaymentSynchronizationOutcome.WAITING)
            throw new CaptureRequeryRejectedException();
        return Result.WAITING;
    }
}
