package com.example.ecsite.service.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.ecsite.config.PaymentReconciliationProperties;


import com.example.ecsite.repository.PaymentTransactionRepository;

@Service
public class PaymentReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentReconciliationItemService itemService;
    private final PaymentReconciliationProperties properties;
    private final Clock clock;
    private final PaymentPostGuardService diagnostics;
    private final Cursor providerKnown = new Cursor();
    private final Cursor providerMissing = new Cursor();
    private static class Cursor { long afterId; long upperId; }

    @Autowired
    public PaymentReconciliationService(
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentReconciliationItemService itemService,
            PaymentReconciliationProperties properties, PaymentPostGuardService diagnostics) {

        this(
                paymentTransactionRepository,
                itemService,
                properties,
                Clock.system(ZoneId.of("Asia/Tokyo")), diagnostics);
    }

    PaymentReconciliationService(
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentReconciliationItemService itemService,
            PaymentReconciliationProperties properties,
            Clock clock, PaymentPostGuardService diagnostics) {

        this.paymentTransactionRepository = paymentTransactionRepository;
        this.itemService = itemService;
        this.properties = properties;
        this.clock = clock;
        this.diagnostics = diagnostics;
    }

    public void reconcilePendingTransactions() {

        LocalDateTime cutoff = LocalDateTime.now(clock)
                .minus(properties.pendingAge());

        List<Long> transactionIds = reserve(cutoff, false, properties.batchSize());
        // Diagnostic sampling is independent of the GET quota; no API/result application.
        List<Long> missingProviderIds = reserve(cutoff, true, Math.min(10, properties.batchSize()));

        for (Long transactionId : transactionIds) {
            try {
                itemService.reconcile(transactionId);
            } catch (Exception e) {
                logger.error(
                        "Payment reconciliation failed. transactionId={}",
                        transactionId,
                        e);
            }
        }
        for (Long transactionId : missingProviderIds) {
            try {
                diagnostics.identifyStoppedPending(transactionId);
            } catch (Exception e) {
                logger.error("Payment pending diagnostic failed. transactionId={}", transactionId, e);
            }
        }
    }

    /** Reserve under a local monitor, then release it before HTTP. Other instances may duplicate GET. */
    private synchronized List<Long> reserve(LocalDateTime cutoff, boolean missing, int limit) {
        Cursor cursor = missing ? providerMissing : providerKnown;
        if (cursor.upperId == 0) {
            Long upper = paymentTransactionRepository.reconciliationUpperId(cutoff, missing, PaymentTransactionRepository.PROVIDER_ID_BLANK_CHARACTERS);
            if (upper == null) return List.of();
            cursor.upperId = upper;
        }
        List<Long> ids = paymentTransactionRepository.reconciliationIds(cutoff, missing,
                cursor.afterId, cursor.upperId, PaymentTransactionRepository.PROVIDER_ID_BLANK_CHARACTERS, PageRequest.of(0, limit));
        if (ids.size() < limit || ids.getLast() == cursor.upperId) {
            cursor.afterId = 0;
            cursor.upperId = 0;
        } else {
            cursor.afterId = ids.getLast();
        }
        return ids;
    }
}
