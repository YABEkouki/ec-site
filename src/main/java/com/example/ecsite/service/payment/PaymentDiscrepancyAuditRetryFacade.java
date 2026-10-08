package com.example.ecsite.service.payment;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Each proxy invocation includes its own commit or rollback before returning. */
@Service
public class PaymentDiscrepancyAuditRetryFacade {
    private static final Logger log = LoggerFactory.getLogger(PaymentDiscrepancyAuditRetryFacade.class);
    private static final int MAX_ATTEMPTS = 3;
    private final PaymentDiscrepancyAuditItemService itemService;

    public PaymentDiscrepancyAuditRetryFacade(PaymentDiscrepancyAuditItemService itemService) {
        this.itemService = itemService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void audit(Long paymentId) {
        execute(paymentId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentDiscrepancyAuditResult auditWithResult(Long paymentId) {
        return execute(paymentId);
    }

    private PaymentDiscrepancyAuditResult execute(Long paymentId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return itemService.auditWithResult(paymentId);
            } catch (RuntimeException failure) {
                if (attempt >= MAX_ATTEMPTS || !isRetryable(failure)) {
                    throw failure;
                }
                log.debug("Retrying payment discrepancy audit: paymentId={}, failedAttempt={}", paymentId, attempt);
            }
        }
    }

    private boolean isRetryable(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException) {
                return true;
            }
            if (cause instanceof ConstraintViolationException constraint
                    && "23505".equals(constraint.getSQLState())
                    && "uq_payment_discrepancies_open".equals(constraint.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
