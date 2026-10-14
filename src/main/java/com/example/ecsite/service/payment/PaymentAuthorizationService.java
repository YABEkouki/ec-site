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
    private final PaymentAuthorizationResultService paymentAuthorizationResultService;

    public PaymentAuthorizationService(
            PaymentAuthorizationStartService startService,
            PaymentService paymentService,
            PaymentGateway paymentGateway,
            PaymentAuthorizationResultService paymentAuthorizationResultService,
            PaymentOperationSnapshotService snapshots) {

        this.snapshots = snapshots;
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
                throw new IllegalStateException("決済開始結果が不明のため、カード与信の再送を停止しています。");
            }
            AuthorizationRecovery recovery = paymentGateway.retrieveAuthorization(providerId);
            paymentAuthorizationResultService.apply(snapshot, recovery.result()).requireAccepted();
            if (recovery.result().status() == AuthorizationResultStatus.AUTHORIZED
                    || recovery.result().status() == AuthorizationResultStatus.FAILED) {
                throw new IllegalStateException("カード与信は確定済みです。注文詳細を確認してください。");
            }
            return new PaymentAuthorizationPreparation(start.paymentId(), recovery.clientSecret());
        }

        AuthorizationPreparation preparation = paymentGateway.prepareAuthorization(
                new AuthorizationRequest(
                        start.amount(),
                        start.idempotencyKey()));

        paymentAuthorizationResultService.setProviderPaymentId(start.snapshot(), preparation.providerPaymentId()).requireAccepted();

        return new PaymentAuthorizationPreparation(
                start.paymentId(),
                preparation.clientSecret());
    }

    public AuthorizationResult refreshAuthorization(Long orderId, Long paymentId) {

        PaymentOperationSnapshot snapshot = snapshots.authorization(orderId, paymentId);
        String providerPaymentId = snapshot.providerPaymentId();

        AuthorizationRecovery recovery = paymentGateway.retrieveAuthorization(providerPaymentId);

        AuthorizationResult result = recovery.result();

        paymentAuthorizationResultService.apply(
                snapshot, result).requireAccepted();

        return result;
    }

    public PaymentAuthorizationRecovery recoverAuthorization(Order order) {

        Long paymentId = paymentService.getRecoverableAuthorizationPaymentId(order);

        PaymentOperationSnapshot snapshot = snapshots.authorization(order.getId(), paymentId);
        String providerPaymentId = snapshot.providerPaymentId();

        AuthorizationRecovery recovery = paymentGateway.retrieveAuthorization(providerPaymentId);

        AuthorizationResult result = recovery.result();

        paymentAuthorizationResultService.apply(snapshot, result).requireAccepted();

        return new PaymentAuthorizationRecovery(
                paymentId,
                recovery.result(),
                recovery.clientSecret(),
                determineRecoveryAction(result.status()));
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
