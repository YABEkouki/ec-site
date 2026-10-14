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

        org.mockito.Mockito.lenient().when(resultService.apply(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(PaymentResultApplication.APPLIED);
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

        PaymentCaptureStart start = new PaymentCaptureStart(paymentId, 40L, "pf_test_123", 5_500, "capture-key-123", PaymentStartDisposition.NEW, new PaymentOperationSnapshot(10L, paymentId, 40L, com.example.ecsite.entity.PaymentTransactionType.CAPTURE, "capture-key-123", 5_500, 0, "pf_test_123", com.example.ecsite.entity.PaymentProvider.PAYJP, com.example.ecsite.entity.PaymentTransactionStatus.PENDING, com.example.ecsite.entity.PaymentTransactionInitiatorType.ADMIN, 30L, "admin", "発送処理"));

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
                .apply(org.mockito.ArgumentMatchers.argThat(s -> java.util.Objects.equals(s.orderId(), orderId) && java.util.Objects.equals(s.paymentId(), paymentId) && java.util.Objects.equals(s.initiatorId(), accountId) && java.util.Objects.equals(s.initiatorUsername(), "admin") && java.util.Objects.equals(s.internalNote(), "発送処理")), org.mockito.ArgumentMatchers.eq(result));
    }

    @Test
    void gatewayFailureDoesNotApplyCaptureResult() {

        Long orderId = 10L;
        Long paymentId = 20L;
        Long accountId = 30L;

        PaymentCaptureStart start = new PaymentCaptureStart(paymentId, 40L, "pf_test_123", 5_500, "capture-key-123", PaymentStartDisposition.NEW, new PaymentOperationSnapshot(10L, paymentId, 40L, com.example.ecsite.entity.PaymentTransactionType.CAPTURE, "capture-key-123", 5_500, 0, "pf_test_123", com.example.ecsite.entity.PaymentProvider.PAYJP, com.example.ecsite.entity.PaymentTransactionStatus.PENDING, com.example.ecsite.entity.PaymentTransactionInitiatorType.ADMIN, 30L, "admin", "発送処理"));

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
                .apply(org.mockito.ArgumentMatchers.any(PaymentOperationSnapshot.class), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reusedPendingRetrievesSuccessWithoutPosting() {
        String providerId = "pf_existing";
        when(startService.start(10L, 30L, "admin", null)).thenReturn(new PaymentCaptureStart(20L, 40L, providerId, 5500, "saved-key", PaymentStartDisposition.REUSED, new PaymentOperationSnapshot(10L, 20L, 40L, com.example.ecsite.entity.PaymentTransactionType.CAPTURE, "saved-key", 5500, 0, providerId, com.example.ecsite.entity.PaymentProvider.PAYJP, com.example.ecsite.entity.PaymentTransactionStatus.PENDING, com.example.ecsite.entity.PaymentTransactionInitiatorType.ADMIN, 30L, "admin", "発送処理")));
        when(paymentGateway.retrievePaymentFlow(providerId)).thenReturn(
                new com.example.ecsite.payment.PaymentFlowState(providerId,
                        com.example.ecsite.payment.PaymentFlowStatus.SUCCEEDED, null, null));
        assertEquals(CaptureResultStatus.CAPTURED, service.captureForShipment(10L, 30L, "admin", null).status());
        verify(paymentGateway, never()).capture(org.mockito.ArgumentMatchers.any());
        verify(paymentGateway).retrievePaymentFlow(providerId);
    }

    @Test
    void reusedPendingWithoutProviderIdStopsWithoutPosting() {
        String providerId = null;
        when(startService.start(10L, 30L, "admin", null)).thenReturn(new PaymentCaptureStart(20L, 40L, providerId, 5500, "saved-key", PaymentStartDisposition.REUSED, new PaymentOperationSnapshot(10L, 20L, 40L, com.example.ecsite.entity.PaymentTransactionType.CAPTURE, "saved-key", 5500, 0, providerId, com.example.ecsite.entity.PaymentProvider.PAYJP, com.example.ecsite.entity.PaymentTransactionStatus.PENDING, com.example.ecsite.entity.PaymentTransactionInitiatorType.ADMIN, 30L, "admin", "発送処理")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.captureForShipment(10L, 30L, "admin", null));
        org.mockito.Mockito.verifyNoInteractions(paymentGateway, resultService);
    }
}
