package com.example.ecsite.service.payment;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

@Service
public class PayJpWebhookCaptureSyncService {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentCaptureResultService resultService;

    public PayJpWebhookCaptureSyncService(
            PaymentRepository paymentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentGateway paymentGateway,
            PaymentCaptureResultService resultService) {

        this.paymentRepository = paymentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentGateway = paymentGateway;
        this.resultService = resultService;
    }

    public void synchronize(String providerPaymentId) {

        Optional<Payment> paymentOptional = paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                providerPaymentId);

        if (paymentOptional.isEmpty()) {
            return;
        }

        Payment payment = paymentOptional.get();

        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return;
        }

        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            return;
        }

        Optional<PaymentTransaction> transactionOptional = paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING);

        if (transactionOptional.isEmpty()) {
            return;
        }

        PaymentTransaction transaction = transactionOptional.get();

        if (transaction.getInitiatorType() != PaymentTransactionInitiatorType.ADMIN) {
            return;
        }

        PaymentFlowState state = paymentGateway.retrievePaymentFlow(providerPaymentId);

        if (state.status() != PaymentFlowStatus.SUCCEEDED) {
            return;
        }

        resultService.apply(
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
}
