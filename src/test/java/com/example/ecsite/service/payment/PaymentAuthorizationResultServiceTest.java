package com.example.ecsite.service.payment;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.service.OrderService;

@ExtendWith(MockitoExtension.class)
class PaymentAuthorizationResultServiceTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private OrderService orderService;

    private PaymentAuthorizationResultService service;

    @BeforeEach
    void setUp() {

        service = new PaymentAuthorizationResultService(
                paymentService,
                orderService);
    }

    @Test
    void applyAuthorizedUpdatesPaymentWithoutCancellingOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;

        AuthorizationResult result =
                new AuthorizationResult(
                        AuthorizationResultStatus.AUTHORIZED,
                        null,
                        null,
                        null);

        service.apply(
                orderId,
                paymentId,
                result);

        verify(paymentService)
                .applyAuthorizationResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .cancelOrderForPaymentFailure(
                        orderId);
    }

    @Test
    void applyFailedUpdatesPaymentAndCancelsOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;

        AuthorizationResult result =
                new AuthorizationResult(
                        AuthorizationResultStatus.FAILED,
                        null,
                        "card_declined",
                        "Card was declined");

        service.apply(
                orderId,
                paymentId,
                result);

        verify(paymentService)
                .applyAuthorizationResult(
                        paymentId,
                        result);

        verify(orderService)
                .cancelOrderForPaymentFailure(
                        orderId);
    }

    @Test
    void applyRequiresActionUpdatesPaymentWithoutCancellingOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;

        AuthorizationResult result =
                new AuthorizationResult(
                        AuthorizationResultStatus.REQUIRES_ACTION,
                        null,
                        null,
                        null);

        service.apply(
                orderId,
                paymentId,
                result);

        verify(paymentService)
                .applyAuthorizationResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .cancelOrderForPaymentFailure(
                        orderId);
    }
}
