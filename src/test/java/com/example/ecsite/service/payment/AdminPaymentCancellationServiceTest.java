package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertSame;
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
    private AdminPaymentCancellationResultService resultService;
    private AdminPaymentCancellationService service;

    @BeforeEach
    void setUp() {

        startService = mock(AdminPaymentCancellationStartService.class);

        paymentGateway = mock(PaymentGateway.class);

        resultService = mock(AdminPaymentCancellationResultService.class);

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
                "idempotency-key");

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.CANCELLED,
                "provider-transaction-id");

        when(startService.start(orderId))
                .thenReturn(start);

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
                .apply(
                        orderId,
                        paymentId,
                        accountId,
                        "admin",
                        "管理者キャンセル",
                        result);
    }

    @Test
    void gatewayFailureDoesNotApplyCancellationResult() {

        Long orderId = 1L;

        PaymentCancellationStart start = new PaymentCancellationStart(
                10L,
                100L,
                "payjp-payment-id",
                "idempotency-key");

        when(startService.start(orderId))
                .thenReturn(start);

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
                        20L,
                        "admin",
                        "管理者キャンセル"));

        verify(resultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }
}
