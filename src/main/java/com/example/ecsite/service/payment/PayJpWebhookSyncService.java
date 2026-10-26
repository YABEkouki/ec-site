package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class PayJpWebhookSyncService {

    private final PayJpWebhookAuthorizationSyncService authorizationSyncService;
    private final PayJpWebhookCaptureSyncService captureSyncService;
    private final PayJpWebhookCancellationSyncService cancellationSyncService;

    private com.example.ecsite.service.orderchange.NewFlowClosureReconciliationService closures;
    @org.springframework.beans.factory.annotation.Autowired
    public PayJpWebhookSyncService(PayJpWebhookAuthorizationSyncService authorizationSyncService,
            PayJpWebhookCaptureSyncService captureSyncService,PayJpWebhookCancellationSyncService cancellationSyncService,
            com.example.ecsite.service.orderchange.NewFlowClosureReconciliationService closures){
        this(authorizationSyncService,captureSyncService,cancellationSyncService);this.closures=closures;
    }

    public PayJpWebhookSyncService(
            PayJpWebhookAuthorizationSyncService authorizationSyncService,
            PayJpWebhookCaptureSyncService captureSyncService,
            PayJpWebhookCancellationSyncService cancellationSyncService) {

        this.authorizationSyncService = authorizationSyncService;
        this.captureSyncService = captureSyncService;
        this.cancellationSyncService = cancellationSyncService;
    }

    public void synchronize(
            String eventType,
            String providerPaymentId) {

        switch (eventType) {

            case "payment_flow.succeeded" ->
                captureSyncService.synchronize(providerPaymentId);

            case "payment_flow.canceled" ->
                cancellationSyncService.synchronize(providerPaymentId);

            default ->
                authorizationSyncService.synchronize(
                        eventType,
                        providerPaymentId);
        }
        // NEVER boundary: nested financial proxies have returned only after their commits completed.
        // Existing synchronization failures propagate; only the independent GET-only trigger is deferred.
        if(closures!=null&&eventType.startsWith("payment_flow."))closures.afterWebhook(providerPaymentId);
    }
}
