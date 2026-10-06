package com.example.ecsite.service.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentDiscrepancy;
import com.example.ecsite.entity.PaymentDiscrepancyRecordStatus;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentDiscrepancyRepository;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

@Service
public class PaymentDiscrepancyAuditItemService {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentDiscrepancyRepository discrepancyRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentDiscrepancyEvaluator evaluator;
    private final Clock clock;

    public PaymentDiscrepancyAuditItemService(
            PaymentRepository paymentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentDiscrepancyRepository discrepancyRepository,
            PaymentGateway paymentGateway,
            PaymentDiscrepancyEvaluator evaluator,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.paymentGateway = paymentGateway;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    @Transactional
    public void audit(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElse(null);

        if (payment == null) {
            return;
        }

        if (payment.getProvider() != PaymentProvider.PAYJP
                || payment.getPaymentMethod() != PaymentMethod.CARD) {
            return;
        }

        String providerPaymentId = payment.getProviderPaymentId();

        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            return;
        }

        boolean hasPendingTransaction =
                paymentTransactionRepository
                        .findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId)
                        .stream()
                        .anyMatch(transaction ->
                                transaction.getStatus()
                                        == PaymentTransactionStatus.PENDING);

        if (hasPendingTransaction) {
            return;
        }

        PaymentFlowState providerState =
                paymentGateway.retrievePaymentFlow(providerPaymentId);

        PaymentDiscrepancyStatus result =
                evaluator.evaluate(
                        payment.getStatus(),
                        providerState.status());

        LocalDateTime now = LocalDateTime.now(clock);

        switch (result) {
            case CONSISTENT -> resolveOpenDiscrepancies(paymentId, now);

            case IN_PROGRESS -> {
                // 状態確定前なので監査情報は変更しない
            }

            case INCONSISTENT ->
                    recordDiscrepancy(payment, providerState, now);
        }
    }

    private void recordDiscrepancy(
            Payment payment,
            PaymentFlowState providerState,
            LocalDateTime detectedAt) {

        discrepancyRepository
                .findByPaymentIdAndLocalStatusAndProviderStatusAndStatus(
                        payment.getId(),
                        payment.getStatus(),
                        providerState.status(),
                        PaymentDiscrepancyRecordStatus.OPEN)
                .ifPresentOrElse(
                        discrepancy ->
                                discrepancy.detectAgain(detectedAt),
                        () -> discrepancyRepository.save(
                                new PaymentDiscrepancy(
                                        payment,
                                        payment.getStatus(),
                                        providerState.status(),
                                        detectedAt)));
    }

    private void resolveOpenDiscrepancies(
            Long paymentId,
            LocalDateTime resolvedAt) {

        List<PaymentDiscrepancy> openDiscrepancies =
                discrepancyRepository.findByPaymentIdAndStatus(
                        paymentId,
                        PaymentDiscrepancyRecordStatus.OPEN);

        openDiscrepancies.forEach(
                discrepancy -> discrepancy.resolve(resolvedAt));
    }
}
