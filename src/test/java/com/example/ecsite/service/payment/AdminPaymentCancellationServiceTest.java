package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.ecsite.payment.CancellationRequest;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayException;

class AdminPaymentCancellationServiceTest {

    private AdminPaymentCancellationStartService startService;
    private PaymentGateway paymentGateway;
    private PaymentCancellationCompletionService resultService;
    private AdminPaymentCancellationService service;

    @BeforeEach
    void setUp() {

        startService = mock(AdminPaymentCancellationStartService.class);

        paymentGateway = mock(PaymentGateway.class);

        resultService = mock(PaymentCancellationCompletionService.class);

        service = new AdminPaymentCancellationService(
                startService,
                paymentGateway,
                resultService);
    }

    @Test
    void cancelStartsCancellationCallsGatewayAndAppliesResult() {

        Long orderId = 1L;
        Long paymentId = 10L;
        Long accountId = 20L;

        PaymentCancellationStart start = new PaymentCancellationStart(
                paymentId,
                100L,
                "payjp-payment-id",
                "idempotency-key",
                PaymentStartDisposition.NEW);

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.CANCELLED,
                "provider-transaction-id");

        when(startService.start(
                orderId,
                accountId,
                "admin",
                "管理者キャンセル"))
                .thenReturn(AdminCancellationStart.provider(start));

        when(paymentGateway.cancelAuthorization(
                new CancellationRequest(
                        "payjp-payment-id",
                        "idempotency-key")))
                .thenReturn(result);

        CancellationResult actual = service.cancel(
                orderId,
                accountId,
                "admin",
                "管理者キャンセル");

        assertSame(result, actual);

        verify(resultService)
                .apply(orderId, start, result);

        verify(startService)
                .start(
                        orderId,
                        accountId,
                        "admin",
                        "管理者キャンセル");
    }

    @Test
    void gatewayFailureDoesNotApplyCancellationResult() {

        Long orderId = 1L;
        Long accountId = 20L;

        PaymentCancellationStart start = new PaymentCancellationStart(
                10L,
                100L,
                "payjp-payment-id",
                "idempotency-key",
                PaymentStartDisposition.NEW);

        when(startService.start(
                orderId,
                accountId,
                "admin",
                "管理者キャンセル"))
                .thenReturn(AdminCancellationStart.provider(start));

        when(paymentGateway.cancelAuthorization(
                new CancellationRequest(
                        "payjp-payment-id",
                        "idempotency-key")))
                .thenThrow(new PaymentGatewayException(
                        "PAY.JP cancellation failed"));

        assertThrows(
                PaymentGatewayException.class,
                () -> service.cancel(
                        orderId,
                        accountId,
                        "admin",
                        "管理者キャンセル"));

        verify(resultService, never())
                .apply(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reusedPendingRetrievesSuccessWithoutPosting() {
        String providerId = "pf_existing";
        when(startService.start(10L, 30L, "admin", null)).thenReturn(AdminCancellationStart.provider(new PaymentCancellationStart(20L, 40L, providerId, "saved-key", PaymentStartDisposition.REUSED)));
        when(paymentGateway.retrievePaymentFlow(providerId)).thenReturn(
                new com.example.ecsite.payment.PaymentFlowState(providerId,
                        com.example.ecsite.payment.PaymentFlowStatus.CANCELED, null, null));
        assertEquals(CancellationResultStatus.CANCELLED, service.cancel(10L, 30L, "admin", null).status());
        verify(paymentGateway, never()).cancelAuthorization(org.mockito.ArgumentMatchers.any());
        verify(paymentGateway).retrievePaymentFlow(providerId);
    }

    @Test
    void reusedPendingWithoutProviderIdStopsWithoutPosting() {
        String providerId = null;
        when(startService.start(10L, 30L, "admin", null)).thenReturn(AdminCancellationStart.provider(new PaymentCancellationStart(20L, 40L, providerId, "saved-key", PaymentStartDisposition.REUSED)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.cancel(10L, 30L, "admin", null));
        org.mockito.Mockito.verifyNoInteractions(paymentGateway, resultService);
    }
}
