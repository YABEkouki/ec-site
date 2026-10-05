package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.payment.CancellationRequest;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayException;

@ExtendWith(MockitoExtension.class)
class PaymentCancellationServiceTest {

    @Mock
    private PaymentCancellationStartService startService;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentCancellationResultService resultService;

    private PaymentCancellationService service;

    @BeforeEach
    void setUp() {

        service = new PaymentCancellationService(
                startService,
                paymentGateway,
                resultService);
    }

    @Test
    void cancelForUserCallsGatewayAndAppliesResult() {

        Long orderId = 10L;
        Long userId = 20L;
        String username = "testuser";

        PaymentCancellationStart start = new PaymentCancellationStart(
                30L,
                40L,
                "pf_test_123",
                "cancel-key-123");

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.CANCELLED,
                "pf_test_123");

        when(startService.start(
                orderId,
                userId,
                username))
                .thenReturn(start);

        when(paymentGateway.cancelAuthorization(
                org.mockito.ArgumentMatchers.any(
                        CancellationRequest.class)))
                .thenReturn(result);

        CancellationResult actual = service.cancelForUser(
                orderId,
                userId,
                username);

        assertEquals(
                result,
                actual);

        ArgumentCaptor<CancellationRequest> captor = ArgumentCaptor.forClass(
                CancellationRequest.class);

        verify(paymentGateway)
                .cancelAuthorization(
                        captor.capture());

        assertEquals(
                "pf_test_123",
                captor.getValue().providerPaymentId());

        assertEquals(
                "cancel-key-123",
                captor.getValue().idempotencyKey());

        verify(resultService)
                .apply(
                        orderId,
                        30L,
                        userId,
                        username,
                        result);

        verify(startService)
                .start(
                        orderId,
                        userId,
                        username);
    }

    @Test
    void gatewayFailureLeavesResultProcessingUntouched() {

        Long orderId = 10L;
        Long userId = 20L;
        String username = "testuser";

        PaymentCancellationStart start = new PaymentCancellationStart(
                30L,
                40L,
                "pf_test_123",
                "cancel-key-123");

        when(startService.start(
                orderId,
                userId,
                username))
                .thenReturn(start);

        when(paymentGateway.cancelAuthorization(
                org.mockito.ArgumentMatchers.any(
                        CancellationRequest.class)))
                .thenThrow(new PaymentGatewayException(
                        "PAY.JP cancellation failed",
                        new RuntimeException("timeout")));

        assertThrows(
                PaymentGatewayException.class,
                () -> service.cancelForUser(
                        orderId,
                        userId,
                        username));

        verify(resultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }
}
