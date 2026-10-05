package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.example.ecsite.payment.CaptureRequest;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayException;

class PaymentCaptureServiceTest {

    private PaymentCaptureStartService startService;
    private PaymentGateway paymentGateway;
    private PaymentCaptureResultService resultService;
    private PaymentCaptureService service;

    @BeforeEach
    void setUp() {

        startService = Mockito.mock(PaymentCaptureStartService.class);

        paymentGateway = Mockito.mock(PaymentGateway.class);

        resultService = Mockito.mock(PaymentCaptureResultService.class);

        service = new PaymentCaptureService(
                startService,
                paymentGateway,
                resultService);
    }

    @Test
    void captureForShipmentStartsCaptureCallsGatewayAndAppliesResult() {

        Long orderId = 10L;
        Long paymentId = 20L;
        Long accountId = 30L;

        PaymentCaptureStart start = new PaymentCaptureStart(
                paymentId,
                40L,
                "pf_test_123",
                5_500,
                "capture-key-123");

        CaptureResult result = new CaptureResult(
                CaptureResultStatus.CAPTURED,
                "pf_test_123",
                null,
                null);

        when(startService.start(
                orderId,
                accountId,
                "admin",
                "発送処理"))
                .thenReturn(start);

        when(paymentGateway.capture(
                new CaptureRequest(
                        "pf_test_123",
                        5_500,
                        "capture-key-123")))
                .thenReturn(result);

        CaptureResult actual = service.captureForShipment(
                orderId,
                accountId,
                "admin",
                "発送処理");

        assertEquals(result, actual);

        InOrder inOrder = inOrder(
                startService,
                paymentGateway,
                resultService);

        inOrder.verify(startService)
                .start(
                        orderId,
                        accountId,
                        "admin",
                        "発送処理");

        inOrder.verify(paymentGateway)
                .capture(
                        new CaptureRequest(
                                "pf_test_123",
                                5_500,
                                "capture-key-123"));

        inOrder.verify(resultService)
                .apply(
                        orderId,
                        paymentId,
                        accountId,
                        "admin",
                        "発送処理",
                        result);
    }

    @Test
    void gatewayFailureDoesNotApplyCaptureResult() {

        Long orderId = 10L;
        Long paymentId = 20L;
        Long accountId = 30L;

        PaymentCaptureStart start = new PaymentCaptureStart(
                paymentId,
                40L,
                "pf_test_123",
                5_500,
                "capture-key-123");

        when(startService.start(
                orderId,
                accountId,
                "admin",
                "発送処理"))
                .thenReturn(start);

        PaymentGatewayException exception = new PaymentGatewayException(
                "Failed to capture PAY.JP Payment Flow",
                new RuntimeException("timeout"));

        when(paymentGateway.capture(
                new CaptureRequest(
                        "pf_test_123",
                        5_500,
                        "capture-key-123")))
                .thenThrow(exception);

        PaymentGatewayException thrown = assertThrows(
                PaymentGatewayException.class,
                () -> service.captureForShipment(
                        orderId,
                        30L,
                        "admin",
                        "発送処理"));

        assertEquals(exception, thrown);

        verify(resultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }
}
