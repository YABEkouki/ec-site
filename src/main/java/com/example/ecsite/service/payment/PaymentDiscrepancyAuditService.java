package com.example.ecsite.service.payment;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.ecsite.config.PaymentDiscrepancyAuditProperties;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.repository.PaymentRepository;

@Service
public class PaymentDiscrepancyAuditService {

    private static final Logger log = LoggerFactory.getLogger(PaymentDiscrepancyAuditService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentDiscrepancyAuditRetryFacade itemService;
    private final PaymentDiscrepancyAuditProperties properties;

    private long lastPaymentId;

    public PaymentDiscrepancyAuditService(
            PaymentRepository paymentRepository,
            PaymentDiscrepancyAuditRetryFacade itemService,
            PaymentDiscrepancyAuditProperties properties) {
        this.paymentRepository = paymentRepository;
        this.itemService = itemService;
        this.properties = properties;
    }

    public synchronized void auditPayments() {
        List<Long> paymentIds = findPaymentIdsAfter(lastPaymentId);

        if (paymentIds.isEmpty() && lastPaymentId > 0L) {
            lastPaymentId = 0L;
            paymentIds = findPaymentIdsAfter(lastPaymentId);
        }

        for (Long paymentId : paymentIds) {
            try {
                itemService.audit(paymentId);
            } catch (Exception e) {
                log.error(
                        "Failed to audit payment discrepancy: paymentId={}",
                        paymentId,
                        e);
            }
        }

        if (!paymentIds.isEmpty()) {
            lastPaymentId = paymentIds.get(paymentIds.size() - 1);
        }
    }

    private List<Long> findPaymentIdsAfter(long paymentId) {
        return paymentRepository
                .findByProviderAndPaymentMethodAndProviderPaymentIdIsNotNullAndIdGreaterThanOrderByIdAsc(
                        PaymentProvider.PAYJP,
                        PaymentMethod.CARD,
                        paymentId,
                        PageRequest.of(
                                0,
                                properties.batchSize()))
                .stream()
                .map(Payment::getId)
                .toList();
    }
}
