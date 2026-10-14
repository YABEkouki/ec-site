package com.example.ecsite.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.PaymentTransactionType;

@Service
@Transactional(propagation = Propagation.NEVER)
public class PayJpWebhookCaptureSyncService {
    private static final Logger log = LoggerFactory.getLogger(PayJpWebhookCaptureSyncService.class);
    private final PaymentOperationSnapshotService snapshots;
    private final PaymentOperationSyncService synchronization;

    public PayJpWebhookCaptureSyncService(PaymentOperationSnapshotService snapshots, PaymentOperationSyncService synchronization) {
        this.snapshots = snapshots;
        this.synchronization = synchronization;
    }

    public PaymentSynchronizationOutcome synchronize(String providerPaymentId) {
        PaymentOperationSnapshot snapshot = snapshots.pendingForProvider(providerPaymentId, PaymentTransactionType.CAPTURE).orElse(null);
        if (snapshot == null) {
            log.debug("Webhook synchronization skipped: operation=CAPTURE, reason=NO_PENDING_OPERATION");
            return PaymentSynchronizationOutcome.SKIPPED;
        }
        PaymentSynchronizationOutcome outcome = synchronization.synchronize(snapshot);
        if (outcome == PaymentSynchronizationOutcome.CONFLICT)
            throw new IllegalStateException("決済結果と現在の操作が一致しません。");
        return outcome;
    }
}
