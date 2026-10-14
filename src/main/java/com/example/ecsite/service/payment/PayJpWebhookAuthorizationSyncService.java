package com.example.ecsite.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.PaymentTransactionType;

@Service
@Transactional(propagation = Propagation.NEVER)
public class PayJpWebhookAuthorizationSyncService {
    private static final Logger log = LoggerFactory.getLogger(PayJpWebhookAuthorizationSyncService.class);
    private static final java.util.Set<String> AUTHORIZATION_EVENTS = java.util.Set.of(
            "payment_flow.requires_action", "payment_flow.processing",
            "payment_flow.amount_capturable_updated", "payment_flow.payment_failed");
    private final PaymentOperationSnapshotService snapshots;
    private final PaymentOperationSyncService synchronization;

    public PayJpWebhookAuthorizationSyncService(PaymentOperationSnapshotService snapshots, PaymentOperationSyncService synchronization) {
        this.snapshots = snapshots;
        this.synchronization = synchronization;
    }

    public PaymentSynchronizationOutcome synchronize(String eventType, String providerPaymentId) {
        if (!AUTHORIZATION_EVENTS.contains(eventType)) return PaymentSynchronizationOutcome.SKIPPED;
        PaymentOperationSnapshot snapshot = snapshots.pendingForProvider(providerPaymentId, PaymentTransactionType.AUTHORIZE).orElse(null);
        if (snapshot == null) {
            log.debug("Webhook synchronization skipped: operation=AUTHORIZE, reason=NO_PENDING_OPERATION");
            return PaymentSynchronizationOutcome.SKIPPED;
        }
        PaymentSynchronizationOutcome outcome = synchronization.synchronize(snapshot);
        if (outcome == PaymentSynchronizationOutcome.CONFLICT)
            throw new IllegalStateException("決済結果と現在の操作が一致しません。");
        return outcome;
    }
}
