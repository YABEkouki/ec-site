package com.example.ecsite.service.payment;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;

/** Retries observation transactions only. Never performs delivery or schedules work. */
@Service
public class PaymentAuditNotificationObservationService {
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;
    private final PaymentDiscrepancyAuditNotificationProperties properties;
    private final PaymentAuditNotificationObservationTransaction transaction;

    public PaymentAuditNotificationObservationService(PaymentDiscrepancyAuditNotificationProperties properties,
            PaymentAuditNotificationObservationTransaction transaction) {
        this.properties = properties;
        this.transaction = transaction;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void observe() {
        if (!properties.enabled()) return;
        for (int attempt = 1; ; attempt++) {
            try {
                // Separate Spring proxy: returns only after commit, including commit-time failures.
                transaction.observe();
                return;
            } catch (RuntimeException failure) {
                if (attempt >= MAX_TRANSACTION_ATTEMPTS || !isTransactionConflict(failure)) throw failure;
            }
        }
    }

    private static boolean isTransactionConflict(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) return true;
        }
        return false;
    }
}
