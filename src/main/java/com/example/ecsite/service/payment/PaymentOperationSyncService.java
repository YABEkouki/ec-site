package com.example.ecsite.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.payment.*;

/** Shared GET orchestration; each result proxy commits before classification/logging. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentOperationSyncService {
    private static final Logger log = LoggerFactory.getLogger(PaymentOperationSyncService.class);
    private final PaymentGateway gateway;
    private final PaymentAuthorizationResultService authorization;
    private final PaymentCaptureResultService capture;
    private final PaymentCancellationResultService userCancellation;
    private final AdminPaymentCancellationResultService adminCancellation;
    private com.example.ecsite.service.orderchange.OrderReauthorizationService orderChanges;

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentOperationSyncService(PaymentGateway gateway,PaymentAuthorizationResultService authorization,
            PaymentCaptureResultService capture,PaymentCancellationResultService userCancellation,AdminPaymentCancellationResultService adminCancellation,
            com.example.ecsite.service.orderchange.OrderReauthorizationService orderChanges){
        this(gateway,authorization,capture,userCancellation,adminCancellation);this.orderChanges=orderChanges;
    }

    public PaymentOperationSyncService(PaymentGateway gateway, PaymentAuthorizationResultService authorization,
            PaymentCaptureResultService capture, PaymentCancellationResultService userCancellation,
            AdminPaymentCancellationResultService adminCancellation) {
        this.gateway = gateway;
        this.authorization = authorization;
        this.capture = capture;
        this.userCancellation = userCancellation;
        this.adminCancellation = adminCancellation;
    }

    public PaymentSynchronizationOutcome synchronize(PaymentOperationSnapshot snapshot) {
        if(com.example.ecsite.entity.PaymentTransaction.isOrderChangePurpose(snapshot.type(),snapshot.purpose()))
            return orderChanges==null?PaymentSynchronizationOutcome.SKIPPED:orderChanges.synchronize(snapshot);
        if (snapshot.status() != PaymentTransactionStatus.PENDING
                || snapshot.provider() != PaymentProvider.PAYJP
                || snapshot.providerPaymentId() == null || snapshot.providerPaymentId().isBlank()) {
            return report(snapshot, PaymentSynchronizationOutcome.SKIPPED);
        }
        try {
            PaymentSynchronizationOutcome outcome = switch (snapshot.type()) {
                case AUTHORIZE -> {
                    AuthorizationResult result = gateway.retrieveAuthorization(snapshot.providerPaymentId()).result();
                    PaymentResultApplication applied = authorization.apply(snapshot, result);
                    boolean waiting = result.status() == AuthorizationResultStatus.PENDING
                            || result.status() == AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD
                            || result.status() == AuthorizationResultStatus.REQUIRES_CONFIRMATION;
                    yield classify(applied, waiting);
                }
                case CAPTURE -> {
                    if (snapshot.initiatorType() != PaymentTransactionInitiatorType.ADMIN)
                        yield PaymentSynchronizationOutcome.SKIPPED;
                    PaymentFlowState state = gateway.retrievePaymentFlow(snapshot.providerPaymentId());
                    boolean captured = state.status() == PaymentFlowStatus.SUCCEEDED;
                    CaptureResult result = new CaptureResult(captured ? CaptureResultStatus.CAPTURED : CaptureResultStatus.PENDING,
                            state.providerPaymentId(), null, null);
                    yield classify(capture.apply(snapshot, result), !captured);
                }
                case CANCEL -> {
                    if (snapshot.initiatorType() != PaymentTransactionInitiatorType.USER
                            && snapshot.initiatorType() != PaymentTransactionInitiatorType.ADMIN)
                        yield PaymentSynchronizationOutcome.SKIPPED;
                    PaymentFlowState state = gateway.retrievePaymentFlow(snapshot.providerPaymentId());
                    boolean cancelled = state.status() == PaymentFlowStatus.CANCELED;
                    CancellationResult result = new CancellationResult(cancelled ? CancellationResultStatus.CANCELLED : CancellationResultStatus.PENDING,
                            state.providerPaymentId());
                    PaymentResultApplication applied = snapshot.initiatorType() == PaymentTransactionInitiatorType.USER
                            ? userCancellation.apply(snapshot, result) : adminCancellation.apply(snapshot, result);
                    yield classify(applied, !cancelled);
                }
                case REFUND -> PaymentSynchronizationOutcome.SKIPPED;
            };
            return report(snapshot, outcome);
        } catch (RuntimeException failure) {
            log.error("Payment synchronization failed: orderId={}, paymentId={}, transactionId={}, operation={}",
                    snapshot.orderId(), snapshot.paymentId(), snapshot.transactionId(), snapshot.type(), failure);
            throw failure;
        }
    }

    private PaymentSynchronizationOutcome classify(PaymentResultApplication application, boolean waiting) {
        if (application == PaymentResultApplication.APPLIED && waiting) return PaymentSynchronizationOutcome.WAITING;
        return PaymentSynchronizationOutcome.from(application);
    }

    private PaymentSynchronizationOutcome report(PaymentOperationSnapshot snapshot, PaymentSynchronizationOutcome outcome) {
        String message = "Payment synchronization: outcome={}, orderId={}, paymentId={}, transactionId={}, operation={}";
        Object[] arguments = {outcome, snapshot.orderId(), snapshot.paymentId(), snapshot.transactionId(), snapshot.type()};
        if (outcome == PaymentSynchronizationOutcome.CONFLICT) log.warn(message, arguments);
        else if (outcome == PaymentSynchronizationOutcome.APPLIED || outcome == PaymentSynchronizationOutcome.IGNORED_STALE)
            log.info(message, arguments);
        else log.debug(message, arguments);
        return outcome;
    }
}
