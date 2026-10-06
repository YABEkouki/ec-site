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
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.repository.PaymentTransactionRepository;

@Service
public class PaymentReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentReconciliationItemService itemService;
    private final PaymentReconciliationProperties properties;
    private final Clock clock;

    @Autowired
    public PaymentReconciliationService(
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentReconciliationItemService itemService,
            PaymentReconciliationProperties properties) {

        this(
                paymentTransactionRepository,
                itemService,
                properties,
                Clock.system(ZoneId.of("Asia/Tokyo")));
    }

    PaymentReconciliationService(
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentReconciliationItemService itemService,
            PaymentReconciliationProperties properties,
            Clock clock) {

        this.paymentTransactionRepository = paymentTransactionRepository;
        this.itemService = itemService;
        this.properties = properties;
        this.clock = clock;
    }

    public void reconcilePendingTransactions() {

        LocalDateTime cutoff = LocalDateTime.now(clock)
                .minus(properties.pendingAge());

        List<Long> transactionIds = paymentTransactionRepository
                .findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(
                        PaymentTransactionStatus.PENDING,
                        cutoff,
                        PageRequest.of(0, properties.batchSize()))
                .stream()
                .map(PaymentTransaction::getId)
                .toList();

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
    }
}
