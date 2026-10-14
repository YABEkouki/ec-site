package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.AuthorizationPreparation;
import com.example.ecsite.payment.AuthorizationRecovery;
import com.example.ecsite.payment.AuthorizationRequest;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.PaymentGateway;

@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentAuthorizationService {

    private final PaymentAuthorizationStartService startService;
    private final PaymentService paymentService;
    private final PaymentOperationSnapshotService snapshots;
    private final PaymentGateway paymentGateway;
    private final PaymentPostGuardService postGuard;
    private final PaymentAuthorizationResultService paymentAuthorizationResultService;

    public PaymentAuthorizationService(
            PaymentAuthorizationStartService startService,
            PaymentService paymentService,
            PaymentGateway paymentGateway,
            PaymentAuthorizationResultService paymentAuthorizationResultService,
            PaymentOperationSnapshotService snapshots, PaymentPostGuardService postGuard) {

        this.snapshots = snapshots;
        this.postGuard = postGuard;
        this.startService = startService;
        this.paymentService = paymentService;
        this.paymentGateway = paymentGateway;
        this.paymentAuthorizationResultService = paymentAuthorizationResultService;
    }

    public PaymentAuthorizationPreparation prepareAuthorization(
            Order order) {

        PaymentAuthorizationStart start = startService.start(order.getId());

        if (start.disposition() == PaymentStartDisposition.REUSED) {
            PaymentOperationSnapshot snapshot = start.snapshot();
            String providerId = snapshot.providerPaymentId();
            if (providerId == null || providerId.isBlank()) {
                throw new PaymentSafetyStopException(PaymentSafetyStopException.Reason.PROVIDER_ID_MISSING, order.getId());
            }
            AuthorizationRecovery recovery = paymentGateway.retrieveAuthorization(providerId);
            applySafely(snapshot, recovery.result());
            if (recovery.result().status() == AuthorizationResultStatus.AUTHORIZED
                    || recovery.result().status() == AuthorizationResultStatus.FAILED) {
                throw new IllegalStateException("カード与信は確定済みです。注文詳細を確認してください。");
            }
            return new PaymentAuthorizationPreparation(start.paymentId(), recovery.clientSecret());
        }

        postGuard.beforeNewPost(start.snapshot());

        AuthorizationPreparation preparation = paymentGateway.prepareAuthorization(
                new AuthorizationRequest(
                        start.amount(),
                        start.idempotencyKey()));

        try {
            paymentAuthorizationResultService.setProviderPaymentId(start.snapshot(), preparation.providerPaymentId()).requireAccepted();
        } catch (RuntimeException failure) {
            throw new PaymentSafetyStopException(PaymentSafetyStopException.Reason.RESULT_PERSISTENCE_UNKNOWN, order.getId());
        }

        return new PaymentAuthorizationPreparation(
                start.paymentId(),
                preparation.clientSecret());
    }

    public AuthorizationResult refreshAuthorization(Long orderId, Long paymentId) {

        PaymentOperationSnapshot snapshot = snapshots.authorization(orderId, paymentId);
        String providerPaymentId = snapshot.providerPaymentId();

        AuthorizationRecovery recovery = paymentGateway.retrieveAuthorization(providerPaymentId);

        AuthorizationResult result = recovery.result();

        applySafely(snapshot, result);

        return result;
    }

    public PaymentAuthorizationRecovery recoverAuthorization(Order order) {

        Long paymentId = paymentService.getRecoverableAuthorizationPaymentId(order);

        PaymentOperationSnapshot snapshot = snapshots.authorization(order.getId(), paymentId);
        String providerPaymentId = snapshot.providerPaymentId();

        AuthorizationRecovery recovery = paymentGateway.retrieveAuthorization(providerPaymentId);

        AuthorizationResult result = recovery.result();

        applySafely(snapshot, result);

        return new PaymentAuthorizationRecovery(
                paymentId,
                recovery.result(),
                recovery.clientSecret(),
                determineRecoveryAction(result.status()));
    }

    private void applySafely(PaymentOperationSnapshot snapshot, AuthorizationResult result) {
        try {
            paymentAuthorizationResultService.apply(snapshot, result).requireAccepted();
        } catch (RuntimeException failure) {
            throw new PaymentSafetyStopException(PaymentSafetyStopException.Reason.RESULT_PERSISTENCE_UNKNOWN, snapshot.orderId());
        }
    }

    private PaymentAuthorizationRecoveryAction determineRecoveryAction(
            AuthorizationResultStatus status) {

        return switch (status) {
            case REQUIRES_PAYMENT_METHOD,
                    REQUIRES_CONFIRMATION,
                    REQUIRES_ACTION ->
                PaymentAuthorizationRecoveryAction.RESUME_CHECKOUT;

            case PENDING ->
                PaymentAuthorizationRecoveryAction.WAIT;

            case AUTHORIZED ->
                PaymentAuthorizationRecoveryAction.COMPLETED;

            case FAILED ->
                PaymentAuthorizationRecoveryAction.FAILED;
        };
    }

}
