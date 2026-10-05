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
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

@Service
public class PayJpWebhookCancellationSyncService {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentCancellationResultService userResultService;
    private final AdminPaymentCancellationResultService adminResultService;

    public PayJpWebhookCancellationSyncService(
            PaymentRepository paymentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentGateway paymentGateway,
            PaymentCancellationResultService userResultService,
            AdminPaymentCancellationResultService adminResultService) {

        this.paymentRepository = paymentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentGateway = paymentGateway;
        this.userResultService = userResultService;
        this.adminResultService = adminResultService;
    }

    public void synchronize(String providerPaymentId) {

        Optional<Payment> paymentOptional =
                paymentRepository.findByProviderAndProviderPaymentId(
                        PaymentProvider.PAYJP,
                        providerPaymentId);

        if (paymentOptional.isEmpty()) {
            return;
        }

        Payment payment = paymentOptional.get();

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            return;
        }

        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            return;
        }

        Optional<PaymentTransaction> transactionOptional =
                paymentTransactionRepository
                        .findByPaymentIdAndTransactionTypeAndStatus(
                                payment.getId(),
                                PaymentTransactionType.CANCEL,
                                PaymentTransactionStatus.PENDING);

        if (transactionOptional.isEmpty()) {
            return;
        }

        PaymentTransaction transaction = transactionOptional.get();

        PaymentFlowState state =
                paymentGateway.retrievePaymentFlow(providerPaymentId);

        if (state.status() != PaymentFlowStatus.CANCELED) {
            return;
        }

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.CANCELLED,
                state.providerPaymentId());

        if (transaction.getInitiatorType()
                == PaymentTransactionInitiatorType.USER) {

            userResultService.apply(
                    payment.getOrder().getId(),
                    payment.getId(),
                    transaction.getInitiatorId(),
                    transaction.getInitiatorUsername(),
                    result);

            return;
        }

        if (transaction.getInitiatorType()
                == PaymentTransactionInitiatorType.ADMIN) {

            adminResultService.apply(
                    payment.getOrder().getId(),
                    payment.getId(),
                    transaction.getInitiatorId(),
                    transaction.getInitiatorUsername(),
                    transaction.getInternalNote(),
                    result);
        }
    }
}
