package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.AuthorizationPreparation;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.payment.PaymentGatewayException;

@ExtendWith(MockitoExtension.class)
class PaymentAuthorizationServiceTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentAuthorizationResultService paymentAuthorizationResultService;

    @Mock
    private Order order;

    private PaymentAuthorizationService service;

    @BeforeEach
    void setUp() {

        service = new PaymentAuthorizationService(
                paymentService,
                paymentGateway,
                paymentAuthorizationResultService);
    }

    @Test
    void prepareAuthorizationCreatesProviderPaymentAndStoresProviderId() {

        Long paymentId = 10L;
        Long transactionId = 20L;
        int amount = 3300;
        String idempotencyKey = "authorization-idempotency-key";
        String providerPaymentId = "pf_test_123";
        String clientSecret = "client_secret_test";

        PaymentAuthorizationStart start = new PaymentAuthorizationStart(
                paymentId,
                transactionId,
                amount,
                idempotencyKey);

        AuthorizationPreparation preparation = new AuthorizationPreparation(
                providerPaymentId,
                clientSecret);

        when(paymentService.startAuthorization(order))
                .thenReturn(start);

        when(paymentService.findProviderPaymentId(paymentId))
                .thenReturn(java.util.Optional.empty());

        when(paymentGateway.prepareAuthorization(
                new com.example.ecsite.payment.AuthorizationRequest(
                        amount,
                        idempotencyKey)))
                .thenReturn(preparation);

        PaymentAuthorizationPreparation actual = service.prepareAuthorization(order);

        assertSame(
                paymentId,
                actual.paymentId());

        org.junit.jupiter.api.Assertions.assertEquals(
                clientSecret,
                actual.clientSecret());

        InOrder inOrder = inOrder(
                paymentService,
                paymentGateway);

        inOrder.verify(paymentService)
                .startAuthorization(order);

        inOrder.verify(paymentGateway)
                .prepareAuthorization(
                        new com.example.ecsite.payment.AuthorizationRequest(
                                amount,
                                idempotencyKey));

        inOrder.verify(paymentService)
                .setProviderPaymentId(
                        paymentId,
                        providerPaymentId);
    }

    @Test
    void prepareAuthorizationDoesNotStoreProviderIdWhenGatewayFails() {

        Long paymentId = 10L;
        Long transactionId = 20L;
        int amount = 3300;
        String idempotencyKey = "authorization-idempotency-key";

        PaymentAuthorizationStart start = new PaymentAuthorizationStart(
                paymentId,
                transactionId,
                amount,
                idempotencyKey);

        when(paymentService.startAuthorization(order))
                .thenReturn(start);

        when(paymentService.findProviderPaymentId(paymentId))
                .thenReturn(java.util.Optional.empty());

        when(paymentGateway.prepareAuthorization(
                new com.example.ecsite.payment.AuthorizationRequest(
                        amount,
                        idempotencyKey)))
                .thenThrow(
                        new PaymentGatewayException(
                                "PAY.JP communication failed"));

        assertThrows(
                PaymentGatewayException.class,
                () -> service.prepareAuthorization(
                        order));

        verify(paymentService)
                .startAuthorization(order);

        verify(paymentGateway)
                .prepareAuthorization(
                        new com.example.ecsite.payment.AuthorizationRequest(
                                amount,
                                idempotencyKey));

        verify(paymentService, never())
                .setProviderPaymentId(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());

        verifyNoInteractions(
                paymentAuthorizationResultService);
    }

    @Test
    void refreshAuthorizationRetrievesProviderStateAndAppliesResult() {

        Long orderId = 10L;
        Long paymentId = 20L;

        String providerPaymentId = "pf_test_123";

        AuthorizationResult result = new AuthorizationResult(
                AuthorizationResultStatus.AUTHORIZED,
                null,
                null,
                null);

        when(paymentService.getProviderPaymentId(
                paymentId))
                .thenReturn(providerPaymentId);

        when(paymentGateway.retrieveAuthorization(
                providerPaymentId))
                .thenReturn(result);

        AuthorizationResult actual = service.refreshAuthorization(
                orderId,
                paymentId);

        assertSame(
                result,
                actual);

        InOrder inOrder = inOrder(
                paymentService,
                paymentGateway,
                paymentAuthorizationResultService);

        inOrder.verify(paymentService)
                .getProviderPaymentId(
                        paymentId);

        inOrder.verify(paymentGateway)
                .retrieveAuthorization(
                        providerPaymentId);

        inOrder.verify(
                paymentAuthorizationResultService)
                .apply(
                        orderId,
                        paymentId,
                        result);
    }

    @Test
    void refreshAuthorizationDoesNotUpdateLocalStateWhenGatewayFails() {

        Long orderId = 10L;
        Long paymentId = 20L;

        String providerPaymentId = "pf_test_123";

        when(paymentService.getProviderPaymentId(
                paymentId))
                .thenReturn(providerPaymentId);

        when(paymentGateway.retrieveAuthorization(
                providerPaymentId))
                .thenThrow(
                        new PaymentGatewayException(
                                "PAY.JP communication failed"));

        assertThrows(
                PaymentGatewayException.class,
                () -> service.refreshAuthorization(
                        orderId,
                        paymentId));

        verify(paymentService)
                .getProviderPaymentId(
                        paymentId);

        verify(paymentGateway)
                .retrieveAuthorization(
                        providerPaymentId);

        verifyNoInteractions(
                paymentAuthorizationResultService);
    }

    @Test
    void prepareAuthorizationRetriesPendingAuthorizationWithoutProviderPaymentId() {

        Long paymentId = 10L;
        Long transactionId = 20L;
        int amount = 3300;
        String idempotencyKey = "existing-authorization-key";
        String providerPaymentId = "pf_test_123";
        String clientSecret = "client_secret_test";

        PaymentAuthorizationStart start = new PaymentAuthorizationStart(
                paymentId,
                transactionId,
                amount,
                idempotencyKey);

        AuthorizationPreparation preparation = new AuthorizationPreparation(
                providerPaymentId,
                clientSecret);

        when(paymentService.startAuthorization(order))
                .thenReturn(start);

        when(paymentService.findProviderPaymentId(paymentId))
                .thenReturn(java.util.Optional.empty());

        when(paymentGateway.prepareAuthorization(
                new com.example.ecsite.payment.AuthorizationRequest(
                        amount,
                        idempotencyKey)))
                .thenReturn(preparation);

        PaymentAuthorizationPreparation actual = service.prepareAuthorization(order);

        org.junit.jupiter.api.Assertions.assertEquals(
                paymentId,
                actual.paymentId());

        org.junit.jupiter.api.Assertions.assertEquals(
                clientSecret,
                actual.clientSecret());

        verify(paymentGateway)
                .prepareAuthorization(
                        new com.example.ecsite.payment.AuthorizationRequest(
                                amount,
                                idempotencyKey));

        verify(paymentService)
                .setProviderPaymentId(
                        paymentId,
                        providerPaymentId);
    }

    @Test
    void prepareAuthorizationRejectsRetryWhenProviderPaymentIdAlreadyExists() {

        Long paymentId = 10L;
        Long transactionId = 20L;
        int amount = 3300;
        String idempotencyKey = "existing-authorization-key";

        PaymentAuthorizationStart start = new PaymentAuthorizationStart(
                paymentId,
                transactionId,
                amount,
                idempotencyKey);

        when(paymentService.startAuthorization(order))
                .thenReturn(start);

        when(paymentService.findProviderPaymentId(paymentId))
                .thenReturn(java.util.Optional.of("pf_test_123"));

        assertThrows(
                IllegalStateException.class,
                () -> service.prepareAuthorization(order));

        verify(paymentGateway, never())
                .prepareAuthorization(
                        org.mockito.ArgumentMatchers.any());

        verify(paymentService, never())
                .setProviderPaymentId(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());
    }

}
