package com.example.ecsite.service.payment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.payment.AuthorizationRecovery;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentRepository;

class PayJpWebhookAuthorizationSyncServiceTest {

    private PaymentRepository paymentRepository;
    private PaymentGateway paymentGateway;
    private PaymentAuthorizationResultService resultService;
    private PayJpWebhookAuthorizationSyncService service;

    @BeforeEach
    void setUp() {

        paymentRepository = mock(PaymentRepository.class);
        paymentGateway = mock(PaymentGateway.class);
        resultService = mock(PaymentAuthorizationResultService.class);

        service = new PayJpWebhookAuthorizationSyncService(
                paymentRepository,
                paymentGateway,
                resultService);
    }

    @Test
    void authorizationEventRefetchesLatestStateAndAppliesIt() {

        Payment payment = mock(Payment.class);
        Order order = mock(Order.class);

        when(payment.getId()).thenReturn(20L);
        when(payment.getOrder()).thenReturn(order);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        when(order.getId()).thenReturn(10L);

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_test_123"))
                .thenReturn(Optional.of(payment));

        AuthorizationResult result = new AuthorizationResult(
                AuthorizationResultStatus.AUTHORIZED,
                "pf_test_123",
                null,
                null);

        when(paymentGateway.retrieveAuthorization("pf_test_123"))
                .thenReturn(new AuthorizationRecovery(
                        result,
                        null));

        service.synchronize(
                "payment_flow.amount_capturable_updated",
                "pf_test_123");

        verify(paymentGateway)
                .retrieveAuthorization("pf_test_123");

        verify(resultService)
                .apply(
                        10L,
                        20L,
                        result);
    }

    @Test
    void completedLocalAuthorizationIgnoresDelayedAuthorizationWebhook() {

        Payment payment = mock(Payment.class);

        when(payment.getStatus())
                .thenReturn(PaymentStatus.AUTHORIZED);

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_test_123"))
                .thenReturn(Optional.of(payment));

        service.synchronize(
                "payment_flow.amount_capturable_updated",
                "pf_test_123");

        verify(paymentGateway, never())
                .retrieveAuthorization("pf_test_123");
    }

    @Test
    void unrelatedEventIsAcknowledgedWithoutLookingUpPayment() {

        service.synchronize(
                "payment_flow.created",
                "pf_test_123");

        verify(paymentRepository, never())
                .findByProviderAndProviderPaymentId(
                        PaymentProvider.PAYJP,
                        "pf_test_123");

        verify(paymentGateway, never())
                .retrieveAuthorization("pf_test_123");
    }

    @Test
    void captureAndCancellationEventsAreNotAppliedAsAuthorizationResults() {

        service.synchronize(
                "payment_flow.succeeded",
                "pf_test_123");

        service.synchronize(
                "payment_flow.canceled",
                "pf_test_123");

        verify(paymentGateway, never())
                .retrieveAuthorization("pf_test_123");

        verify(resultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingLocalPaymentDoesNotCallPayJp() {

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_unknown"))
                .thenReturn(Optional.empty());

        service.synchronize(
                "payment_flow.processing",
                "pf_unknown");

        verify(paymentGateway, never())
                .retrieveAuthorization("pf_unknown");
    }
}
