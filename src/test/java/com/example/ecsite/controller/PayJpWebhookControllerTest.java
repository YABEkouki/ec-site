package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.ecsite.payment.payjp.PayJpWebhookEvent;
import com.example.ecsite.payment.payjp.PayJpWebhookPaymentFlow;
import com.example.ecsite.payment.payjp.PayJpWebhookTokenVerifier;
import com.example.ecsite.service.payment.PayJpWebhookSyncService;

class PayJpWebhookControllerTest {

    private PayJpWebhookTokenVerifier tokenVerifier;
    private PayJpWebhookSyncService syncService;
    private PayJpWebhookController controller;

    @BeforeEach
    void setUp() {

        tokenVerifier = mock(PayJpWebhookTokenVerifier.class);
        syncService = mock(PayJpWebhookSyncService.class);

        controller = new PayJpWebhookController(
                tokenVerifier,
                syncService);
    }

    @Test
    void validWebhookInvokesSynchronization() {

        when(tokenVerifier.isValid("valid-token"))
                .thenReturn(true);

        PayJpWebhookEvent event = new PayJpWebhookEvent(
                "evt_test_123",
                "payment_flow.processing",
                new PayJpWebhookPaymentFlow(
                        "pf_test_123"));

        var response = controller.receive(
                "valid-token",
                event);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        verify(syncService)
                .synchronize(
                        "payment_flow.processing",
                        "pf_test_123");
    }

    @Test
    void invalidTokenIsRejectedBeforeSynchronization() {

        when(tokenVerifier.isValid("invalid-token"))
                .thenReturn(false);

        var response = controller.receive(
                "invalid-token",
                new PayJpWebhookEvent(
                        "evt_test_123",
                        "payment_flow.processing",
                        new PayJpWebhookPaymentFlow(
                                "pf_test_123")));

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                response.getStatusCode());

        verify(syncService, never())
                .synchronize(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void malformedEventReturnsBadRequest() {

        when(tokenVerifier.isValid("valid-token"))
                .thenReturn(true);

        var response = controller.receive(
                "valid-token",
                new PayJpWebhookEvent(
                        "evt_test_123",
                        "payment_flow.processing",
                        null));

        assertEquals(
                HttpStatus.BAD_REQUEST,
                response.getStatusCode());

        verify(syncService, never())
                .synchronize(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }
}
