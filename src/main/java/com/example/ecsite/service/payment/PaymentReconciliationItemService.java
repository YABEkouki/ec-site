package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.payment.AuthorizationRecovery;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentTransactionRepository;

@Service
public class PaymentReconciliationItemService {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentAuthorizationResultService authorizationResultService;
    private final PaymentCaptureResultService captureResultService;
    private final PaymentCancellationResultService userCancellationResultService;
    private final AdminPaymentCancellationResultService adminCancellationResultService;

    public PaymentReconciliationItemService(
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentGateway paymentGateway,
            PaymentAuthorizationResultService authorizationResultService,
            PaymentCaptureResultService captureResultService,
            PaymentCancellationResultService userCancellationResultService,
            AdminPaymentCancellationResultService adminCancellationResultService) {

        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentGateway = paymentGateway;
        this.authorizationResultService = authorizationResultService;
        this.captureResultService = captureResultService;
        this.userCancellationResultService = userCancellationResultService;
        this.adminCancellationResultService = adminCancellationResultService;
    }

    @Transactional
    public void reconcile(Long transactionId) {

        PaymentTransaction transaction = paymentTransactionRepository
                .findById(transactionId)
                .orElse(null);

        if (transaction == null) {
            return;
        }

        if (transaction.getStatus() != PaymentTransactionStatus.PENDING) {
            return;
        }

        switch (transaction.getTransactionType()) {
            case AUTHORIZE -> reconcileAuthorization(transaction);
            case CAPTURE -> reconcileCapture(transaction);
            case CANCEL -> reconcileCancellation(transaction);
            case REFUND -> {
                // Feature108では対象外
            }
        }
    }

    private void reconcileAuthorization(PaymentTransaction transaction) {

        Payment payment = transaction.getPayment();

        if (payment.getProvider() != PaymentProvider.PAYJP) {
            return;
        }

        String providerPaymentId = payment.getProviderPaymentId();

        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            return;
        }

        AuthorizationRecovery recovery = paymentGateway.retrieveAuthorization(providerPaymentId);

        AuthorizationResult result = recovery.result();

        authorizationResultService.apply(
                payment.getOrder().getId(),
                payment.getId(),
                result);
    }

    private void reconcileCapture(PaymentTransaction transaction) {

        Payment payment = transaction.getPayment();

        if (payment.getProvider() != PaymentProvider.PAYJP) {
            return;
        }

        if (transaction.getInitiatorType() != PaymentTransactionInitiatorType.ADMIN) {
            return;
        }

        String providerPaymentId = payment.getProviderPaymentId();

        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            return;
        }

        PaymentFlowState state = paymentGateway.retrievePaymentFlow(providerPaymentId);

        if (state.status() != PaymentFlowStatus.SUCCEEDED) {
            return;
        }

        captureResultService.apply(
                payment.getOrder().getId(),
                payment.getId(),
                transaction.getInitiatorId(),
                transaction.getInitiatorUsername(),
                transaction.getInternalNote(),
                new CaptureResult(
                        CaptureResultStatus.CAPTURED,
                        state.providerPaymentId(),
                        null,
                        null));
    }

    private void reconcileCancellation(PaymentTransaction transaction) {

        Payment payment = transaction.getPayment();

        if (payment.getProvider() != PaymentProvider.PAYJP) {
            return;
        }

        String providerPaymentId = payment.getProviderPaymentId();

        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            return;
        }

        PaymentTransactionInitiatorType initiatorType = transaction.getInitiatorType();

        if (initiatorType != PaymentTransactionInitiatorType.USER
                && initiatorType != PaymentTransactionInitiatorType.ADMIN) {
            return;
        }

        PaymentFlowState state = paymentGateway.retrievePaymentFlow(providerPaymentId);

        if (state.status() != PaymentFlowStatus.CANCELED) {
            return;
        }

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.CANCELLED,
                state.providerPaymentId());

        if (initiatorType == PaymentTransactionInitiatorType.USER) {

            userCancellationResultService.apply(
                    payment.getOrder().getId(),
                    payment.getId(),
                    transaction.getInitiatorId(),
                    transaction.getInitiatorUsername(),
                    result);

            return;
        }

        adminCancellationResultService.apply(
                payment.getOrder().getId(),
                payment.getId(),
                transaction.getInitiatorId(),
                transaction.getInitiatorUsername(),
                transaction.getInternalNote(),
                result);
    }
}
