package com.example.ecsite.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentReconciliationItemService {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationItemService.class);
    private final PaymentOperationSnapshotService snapshots;
    private final PaymentPostGuardService diagnostics;
    private final PaymentOperationSyncService synchronization;

    public PaymentReconciliationItemService(PaymentOperationSnapshotService snapshots, PaymentOperationSyncService synchronization, PaymentPostGuardService diagnostics) {
        this.diagnostics = diagnostics;
        this.snapshots = snapshots;
        this.synchronization = synchronization;
    }

    public PaymentSynchronizationOutcome reconcile(Long transactionId) {
        PaymentOperationSnapshot snapshot = snapshots.find(transactionId).orElse(null);
        if (snapshot == null) {
            log.debug("Payment reconciliation skipped: transactionId={}, reason=NOT_FOUND", transactionId);
            return PaymentSynchronizationOutcome.SKIPPED;
        }
        if (snapshot.providerPaymentId() == null || snapshot.providerPaymentId().isBlank())
            diagnostics.identifyStoppedPending(transactionId);
        return synchronization.synchronize(snapshot);
    }
}
