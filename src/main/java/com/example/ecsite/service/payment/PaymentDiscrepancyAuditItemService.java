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
        doAudit(paymentId);
    }

    @Transactional
    public PaymentDiscrepancyAuditResult auditWithResult(Long paymentId) {
        return doAudit(paymentId);
    }

    private PaymentDiscrepancyAuditResult doAudit(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElse(null);

        if (payment == null) {
            return skipped(PaymentDiscrepancyAuditResult.SkipReason.PAYMENT_NOT_FOUND);
        }

        if (payment.getProvider() != PaymentProvider.PAYJP
                || payment.getPaymentMethod() != PaymentMethod.CARD) {
            return skipped(PaymentDiscrepancyAuditResult.SkipReason.UNSUPPORTED_PAYMENT);
        }

        String providerPaymentId = payment.getProviderPaymentId();

        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            return skipped(PaymentDiscrepancyAuditResult.SkipReason.MISSING_PROVIDER_PAYMENT_ID);
        }

        boolean hasPendingTransaction =
                paymentTransactionRepository
                        .findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId)
                        .stream()
                        .anyMatch(transaction ->
                                transaction.getStatus()
                                        == PaymentTransactionStatus.PENDING);

        if (hasPendingTransaction) {
            return skipped(PaymentDiscrepancyAuditResult.SkipReason.PENDING_TRANSACTION);
        }

        PaymentFlowState providerState =
                paymentGateway.retrievePaymentFlow(providerPaymentId);

        PaymentDiscrepancyStatus result =
                evaluator.evaluate(
                        payment.getStatus(),
                        providerState.status());

        LocalDateTime now = LocalDateTime.now(clock);

        return switch (result) {
            case CONSISTENT -> new PaymentDiscrepancyAuditResult(
                    PaymentDiscrepancyAuditResult.Status.CONSISTENT,
                    null, providerState.status(), resolveOpenDiscrepancies(paymentId, now));
            case IN_PROGRESS -> new PaymentDiscrepancyAuditResult(
                    PaymentDiscrepancyAuditResult.Status.IN_PROGRESS,
                    null, providerState.status(), List.of());
            case INCONSISTENT -> new PaymentDiscrepancyAuditResult(
                    PaymentDiscrepancyAuditResult.Status.INCONSISTENT,
                    null, providerState.status(), List.of(recordDiscrepancy(payment, providerState, now)));
        };
    }

    private PaymentDiscrepancyAuditResult skipped(PaymentDiscrepancyAuditResult.SkipReason reason) {
        return new PaymentDiscrepancyAuditResult(
                PaymentDiscrepancyAuditResult.Status.SKIPPED, reason, null, List.of());
    }

    private Long recordDiscrepancy(
            Payment payment,
            PaymentFlowState providerState,
            LocalDateTime detectedAt) {

        PaymentDiscrepancy discrepancy = discrepancyRepository
                .findByPaymentIdAndLocalStatusAndProviderStatusAndStatus(
                        payment.getId(),
                        payment.getStatus(),
                        providerState.status(),
                        PaymentDiscrepancyRecordStatus.OPEN)
                .orElse(null);

        if (discrepancy != null) {
            discrepancy.detectAgain(detectedAt);
        } else {
            discrepancy = discrepancyRepository.save(new PaymentDiscrepancy(
                    payment, payment.getStatus(), providerState.status(), detectedAt));
        }

        return discrepancy.getId();
    }

    private List<Long> resolveOpenDiscrepancies(
            Long paymentId,
            LocalDateTime resolvedAt) {

        List<PaymentDiscrepancy> openDiscrepancies =
                discrepancyRepository.findByPaymentIdAndStatus(
                        paymentId,
                        PaymentDiscrepancyRecordStatus.OPEN);

        openDiscrepancies.forEach(
                discrepancy -> discrepancy.resolve(resolvedAt));

        return openDiscrepancies.stream().map(PaymentDiscrepancy::getId).toList();
    }
}
