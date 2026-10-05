package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;

@Service
public class PayJpWebhookSyncService {

    private final PayJpWebhookAuthorizationSyncService authorizationSyncService;
    private final PayJpWebhookCaptureSyncService captureSyncService;
    private final PayJpWebhookCancellationSyncService cancellationSyncService;

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
    }
}
