package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;

@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentReconciliationItemService {

    private final PaymentOperationSnapshotService snapshots;
    private final PaymentGateway paymentGateway;
    private final PaymentAuthorizationResultService authorizationResultService;
    private final PaymentCaptureResultService captureResultService;
    private final PaymentCancellationResultService userCancellationResultService;
    private final AdminPaymentCancellationResultService adminCancellationResultService;

    public PaymentReconciliationItemService(
            PaymentOperationSnapshotService snapshots,
            PaymentGateway paymentGateway,
            PaymentAuthorizationResultService authorizationResultService,
            PaymentCaptureResultService captureResultService,
            PaymentCancellationResultService userCancellationResultService,
            AdminPaymentCancellationResultService adminCancellationResultService) {

        this.snapshots = snapshots;
        this.paymentGateway = paymentGateway;
        this.authorizationResultService = authorizationResultService;
        this.captureResultService = captureResultService;
        this.userCancellationResultService = userCancellationResultService;
        this.adminCancellationResultService = adminCancellationResultService;
    }

    public void reconcile(Long transactionId) {
        PaymentOperationSnapshot snapshot = snapshots.find(transactionId).orElse(null);
        if (snapshot == null || snapshot.status() != PaymentTransactionStatus.PENDING
                || snapshot.provider() != PaymentProvider.PAYJP
                || snapshot.providerPaymentId() == null || snapshot.providerPaymentId().isBlank()) return;
        switch (snapshot.type()) {
            case AUTHORIZE -> authorizationResultService.apply(snapshot,
                    paymentGateway.retrieveAuthorization(snapshot.providerPaymentId()).result()).requireAccepted();
            case CAPTURE -> {
                if (snapshot.initiatorType() != PaymentTransactionInitiatorType.ADMIN) return;
                PaymentFlowState state = paymentGateway.retrievePaymentFlow(snapshot.providerPaymentId());
                if (state.status() == PaymentFlowStatus.SUCCEEDED)
                    captureResultService.apply(snapshot, new CaptureResult(CaptureResultStatus.CAPTURED,
                            state.providerPaymentId(), null, null)).requireAccepted();
            }
            case CANCEL -> {
                if (snapshot.initiatorType() != PaymentTransactionInitiatorType.USER
                        && snapshot.initiatorType() != PaymentTransactionInitiatorType.ADMIN) return;
                PaymentFlowState state = paymentGateway.retrievePaymentFlow(snapshot.providerPaymentId());
                if (state.status() != PaymentFlowStatus.CANCELED) return;
                CancellationResult result = new CancellationResult(CancellationResultStatus.CANCELLED, state.providerPaymentId());
                PaymentResultApplication outcome = snapshot.initiatorType() == PaymentTransactionInitiatorType.USER
                        ? userCancellationResultService.apply(snapshot, result)
                        : adminCancellationResultService.apply(snapshot, result);
                outcome.requireAccepted();
            }
            case REFUND -> { /* Unsupported operation. */ }
        }
    }
}
