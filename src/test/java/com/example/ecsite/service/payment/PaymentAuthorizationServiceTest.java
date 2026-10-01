package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.AuthorizationPreparation;
import com.example.ecsite.payment.AuthorizationRequest;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayException;

class PaymentAuthorizationServiceTest {

    private PaymentService paymentService;
    private PaymentGateway paymentGateway;
    private PaymentAuthorizationService service;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        paymentGateway = mock(PaymentGateway.class);

        service = new PaymentAuthorizationService(
                paymentService,
                paymentGateway);
    }

    @Test
    void prepareAuthorizationCreatesPaymentFlowAndStoresProviderId() {

        Order order = mock(Order.class);

        PaymentAuthorizationStart start = new PaymentAuthorizationStart(
                10L,
                20L,
                12_345,
                "idempotency-key-123");

        when(paymentService.startAuthorization(order))
                .thenReturn(start);

        when(paymentGateway.prepareAuthorization(
                new AuthorizationRequest(
                        12_345,
                        "idempotency-key-123")))
                .thenReturn(
                        new AuthorizationPreparation(
                                "pf_test_123",
                                "client_secret_test"));

        PaymentAuthorizationPreparation result = service.prepareAuthorization(order);

        assertEquals(10L, result.paymentId());
        assertEquals(
                "client_secret_test",
                result.clientSecret());

        verify(paymentService).setProviderPaymentId(
                10L,
                "pf_test_123");

        InOrder inOrder = inOrder(
                paymentService,
                paymentGateway);

        inOrder.verify(paymentService)
                .startAuthorization(order);

        inOrder.verify(paymentGateway)
                .prepareAuthorization(
                        new AuthorizationRequest(
                                12_345,
                                "idempotency-key-123"));

        inOrder.verify(paymentService)
                .setProviderPaymentId(
                        10L,
                        "pf_test_123");
    }

    @Test
    void prepareAuthorizationLeavesPaymentPendingWhenGatewayFails() {

        Order order = mock(Order.class);

        PaymentAuthorizationStart start = new PaymentAuthorizationStart(
                10L,
                20L,
                12_345,
                "idempotency-key-123");

        when(paymentService.startAuthorization(order))
                .thenReturn(start);

        when(paymentGateway.prepareAuthorization(
                new AuthorizationRequest(
                        12_345,
                        "idempotency-key-123")))
                .thenThrow(
                        new PaymentGatewayException(
                                "PAY.JP communication failed"));

        assertThrows(
                PaymentGatewayException.class,
                () -> service.prepareAuthorization(order));

        verify(paymentService, never())
                .setProviderPaymentId(
                        anyLong(),
                        anyString());
    }

    @Test
    void refreshAuthorizationRetrievesProviderStateAndAppliesResult() {

        when(paymentService.getProviderPaymentId(10L))
                .thenReturn("pf_test_123");

        AuthorizationResult authorizationResult = new AuthorizationResult(
                AuthorizationResultStatus.AUTHORIZED,
                null,
                null,
                null);

        when(paymentGateway.retrieveAuthorization(
                "pf_test_123"))
                .thenReturn(authorizationResult);

        AuthorizationResult result = service.refreshAuthorization(10L);

        assertEquals(
                AuthorizationResultStatus.AUTHORIZED,
                result.status());

        InOrder inOrder = inOrder(
                paymentService,
                paymentGateway);

        inOrder.verify(paymentService)
                .getProviderPaymentId(10L);

        inOrder.verify(paymentGateway)
                .retrieveAuthorization(
                        "pf_test_123");

        inOrder.verify(paymentService)
                .applyAuthorizationResult(
                        10L,
                        authorizationResult);
    }

    @Test
    void refreshAuthorizationDoesNotUpdateLocalStateWhenGatewayFails() {

        when(paymentService.getProviderPaymentId(10L))
                .thenReturn("pf_test_123");

        when(paymentGateway.retrieveAuthorization(
                "pf_test_123"))
                .thenThrow(
                        new PaymentGatewayException(
                                "PAY.JP communication failed"));

        assertThrows(
                PaymentGatewayException.class,
                () -> service.refreshAuthorization(10L));

        verify(paymentService, never())
                .applyAuthorizationResult(
                        anyLong(),
                        any());
    }

}
