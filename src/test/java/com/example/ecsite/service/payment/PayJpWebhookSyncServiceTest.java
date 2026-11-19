package com.example.ecsite.service.payment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayJpWebhookSyncServiceTest {

    private PayJpWebhookAuthorizationSyncService authorizationSyncService;
    private PayJpWebhookCaptureSyncService captureSyncService;
    private PayJpWebhookCancellationSyncService cancellationSyncService;
    private PayJpWebhookSyncService service;

    @BeforeEach
    void setUp() {

        authorizationSyncService =
                mock(PayJpWebhookAuthorizationSyncService.class);
        captureSyncService =
                mock(PayJpWebhookCaptureSyncService.class);
        cancellationSyncService =
                mock(PayJpWebhookCancellationSyncService.class);

        service = new PayJpWebhookSyncService(
                authorizationSyncService,
                captureSyncService,
                cancellationSyncService);
    }

    @Test
    void succeededEventIsRoutedToCaptureSynchronization() {

        service.synchronize(
                "payment_flow.succeeded",
                "pf_test_123");

        verify(captureSyncService)
                .synchronize("pf_test_123");

        verify(cancellationSyncService, never())
                .synchronize("pf_test_123");

        verify(authorizationSyncService, never())
                .synchronize(
                        "payment_flow.succeeded",
                        "pf_test_123");
    }

    /** Feature135-B2: only when no pending Cancel exists does a canceled Flow settle a pending shipment capture. */
    @Test
    void canceledEventWithoutAPendingCancelFallsBackToTheCapture() {
        when(cancellationSyncService.synchronize("pf_test_123")).thenReturn(PaymentSynchronizationOutcome.SKIPPED);
        service.synchronize("payment_flow.canceled", "pf_test_123");
        var order = inOrder(cancellationSyncService, captureSyncService);
        order.verify(cancellationSyncService).synchronize("pf_test_123");
        order.verify(captureSyncService).synchronize("pf_test_123");
        when(cancellationSyncService.synchronize("pf_test_456")).thenReturn(PaymentSynchronizationOutcome.APPLIED);
        service.synchronize("payment_flow.canceled", "pf_test_456");
        verify(captureSyncService, never()).synchronize("pf_test_456");
    }

    @Test
    void canceledEventIsRoutedToCancellationSynchronization() {

        service.synchronize(
                "payment_flow.canceled",
                "pf_test_123");

        verify(cancellationSyncService)
                .synchronize("pf_test_123");

        verify(captureSyncService, never())
                .synchronize("pf_test_123");

        verify(authorizationSyncService, never())
                .synchronize(
                        "payment_flow.canceled",
                        "pf_test_123");
    }

    @Test
    void authorizationEventIsRoutedToAuthorizationSynchronization() {

        service.synchronize(
                "payment_flow.processing",
                "pf_test_123");

        verify(authorizationSyncService)
                .synchronize(
                        "payment_flow.processing",
                        "pf_test_123");

        verify(captureSyncService, never())
                .synchronize("pf_test_123");

        verify(cancellationSyncService, never())
                .synchronize("pf_test_123");
    }

    @Test
    void unknownEventIsDelegatedToAuthorizationSynchronization() {

        service.synchronize(
                "payment_flow.unknown",
                "pf_test_123");

        verify(authorizationSyncService)
                .synchronize(
                        "payment_flow.unknown",
                        "pf_test_123");

        verify(captureSyncService, never())
                .synchronize("pf_test_123");

        verify(cancellationSyncService, never())
                .synchronize("pf_test_123");
    }
}
