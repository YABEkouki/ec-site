package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.service.OrderService;

@ExtendWith(MockitoExtension.class)
class PaymentCancellationResultServiceTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private OrderService orderService;

    private PaymentCancellationResultService service;

    @BeforeEach
    void setUp() {

        service = new PaymentCancellationResultService(
                paymentService,
                orderService);
    }

    @Test
    void cancelledResultUpdatesPaymentAndCancelsOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;
        Long userId = 30L;
        String username = "testuser";

        CancellationResult result =
                new CancellationResult(
                        CancellationResultStatus.CANCELLED,
                        "pf_test_123");

        service.apply(
                orderId,
                paymentId,
                userId,
                username,
                result);

        verify(paymentService)
                .validatePaymentBelongsToOrder(
                        paymentId,
                        orderId);

        verify(paymentService)
                .applyCancellationResult(
                        paymentId,
                        result);

        verify(orderService)
                .cancelOrderForUserAfterPaymentCancellation(
                        orderId,
                        userId,
                        username);
    }

    @Test
    void pendingResultUpdatesPaymentButDoesNotCancelOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;
        Long userId = 30L;
        String username = "testuser";

        CancellationResult result =
                new CancellationResult(
                        CancellationResultStatus.PENDING,
                        "pf_test_123");

        service.apply(
                orderId,
                paymentId,
                userId,
                username,
                result);

        verify(paymentService)
                .validatePaymentBelongsToOrder(
                        paymentId,
                        orderId);

        verify(paymentService)
                .applyCancellationResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .cancelOrderForUserAfterPaymentCancellation(
                        orderId,
                        userId,
                        username);
    }

    @Test
    void mismatchedPaymentDoesNotUpdateAnything() {

        Long orderId = 10L;
        Long paymentId = 20L;
        Long userId = 30L;
        String username = "testuser";

        CancellationResult result =
                new CancellationResult(
                        CancellationResultStatus.CANCELLED,
                        "pf_test_123");

        doThrow(new IllegalArgumentException(
                "決済情報と注文が一致しません。"))
                .when(paymentService)
                .validatePaymentBelongsToOrder(
                        paymentId,
                        orderId);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.apply(
                        orderId,
                        paymentId,
                        userId,
                        username,
                        result));

        verify(paymentService, never())
                .applyCancellationResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .cancelOrderForUserAfterPaymentCancellation(
                        orderId,
                        userId,
                        username);
    }
}
