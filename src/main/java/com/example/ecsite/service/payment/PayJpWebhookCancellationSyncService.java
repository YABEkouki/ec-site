package com.example.ecsite.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.PaymentTransactionType;

@Service
@Transactional(propagation = Propagation.NEVER)
public class PayJpWebhookCancellationSyncService {
    private static final Logger log = LoggerFactory.getLogger(PayJpWebhookCancellationSyncService.class);
    private final PaymentOperationSnapshotService snapshots;
    private final PaymentOperationSyncService synchronization;

    public PayJpWebhookCancellationSyncService(PaymentOperationSnapshotService snapshots, PaymentOperationSyncService synchronization) {
        this.snapshots = snapshots;
        this.synchronization = synchronization;
    }

    public PaymentSynchronizationOutcome synchronize(String providerPaymentId) {
        PaymentOperationSnapshot snapshot = snapshots.pendingForProvider(providerPaymentId, PaymentTransactionType.CANCEL).orElse(null);
        // A new candidate Flow may be canceled before any Cancel transaction exists.
        // Keep initial-order authorization semantics unchanged; only the dedicated result route may clean up a candidate.
        if(snapshot==null){
            var authorization=snapshots.pendingForProvider(providerPaymentId,PaymentTransactionType.AUTHORIZE).orElse(null);
            if(authorization!=null&&authorization.purpose()==com.example.ecsite.entity.PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION)
                snapshot=authorization;
        }
        if (snapshot == null) {
            log.debug("Webhook synchronization skipped: operation=CANCEL, reason=NO_PENDING_OPERATION");
            return PaymentSynchronizationOutcome.SKIPPED;
        }
        PaymentSynchronizationOutcome outcome = synchronization.synchronize(snapshot);
        if (outcome == PaymentSynchronizationOutcome.CONFLICT)
            throw new IllegalStateException("決済結果と現在の操作が一致しません。");
        return outcome;
    }
}
