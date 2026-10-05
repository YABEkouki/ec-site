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
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

class PayJpWebhookCaptureSyncServiceTest {

    private PaymentRepository paymentRepository;
    private PaymentTransactionRepository paymentTransactionRepository;
    private PaymentGateway paymentGateway;
    private PaymentCaptureResultService resultService;
    private PayJpWebhookCaptureSyncService service;

    @BeforeEach
    void setUp() {

        paymentRepository = mock(PaymentRepository.class);
        paymentTransactionRepository =
                mock(PaymentTransactionRepository.class);
        paymentGateway = mock(PaymentGateway.class);
        resultService = mock(PaymentCaptureResultService.class);

        service = new PayJpWebhookCaptureSyncService(
                paymentRepository,
                paymentTransactionRepository,
                paymentGateway,
                resultService);
    }

    @Test
    void succeededFlowCompletesPendingCaptureWithSavedInitiator() {

        Payment payment = authorizedPayment();
        PaymentTransaction transaction = adminCaptureTransaction();

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_test_123"))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        20L,
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        PaymentFlowState state = new PaymentFlowState(
                "pf_test_123",
                PaymentFlowStatus.SUCCEEDED,
                null,
                null);

        when(paymentGateway.retrievePaymentFlow("pf_test_123"))
                .thenReturn(state);

        service.synchronize("pf_test_123");

        verify(resultService)
                .apply(
                        10L,
                        20L,
                        30L,
                        "admin",
                        "発送処理",
                        new CaptureResult(
                                CaptureResultStatus.CAPTURED,
                                "pf_test_123",
                                null,
                                null));
    }

    @Test
    void missingLocalPaymentDoesNotCallPayJp() {

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_unknown"))
                .thenReturn(Optional.empty());

        service.synchronize("pf_unknown");

        verify(paymentGateway, never())
                .retrievePaymentFlow("pf_unknown");
    }

    @Test
    void alreadyCapturedPaymentIgnoresDelayedWebhook() {

        Payment payment = mock(Payment.class);

        when(payment.getStatus())
                .thenReturn(PaymentStatus.CAPTURED);

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_test_123"))
                .thenReturn(Optional.of(payment));

        service.synchronize("pf_test_123");

        verify(paymentGateway, never())
                .retrievePaymentFlow("pf_test_123");
    }

    @Test
    void missingPendingCaptureDoesNotCallPayJp() {

        Payment payment = authorizedPayment();

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_test_123"))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        20L,
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.empty());

        service.synchronize("pf_test_123");

        verify(paymentGateway, never())
                .retrievePaymentFlow("pf_test_123");

        verify(resultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonAdminInitiatorDoesNotCallPayJp() {

        Payment payment = authorizedPayment();
        PaymentTransaction transaction = mock(PaymentTransaction.class);

        when(transaction.getInitiatorType())
                .thenReturn(PaymentTransactionInitiatorType.USER);

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_test_123"))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        20L,
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        service.synchronize("pf_test_123");

        verify(paymentGateway, never())
                .retrievePaymentFlow("pf_test_123");
    }

    @Test
    void unfinishedProviderFlowDoesNotCompleteCapture() {

        Payment payment = authorizedPayment();
        PaymentTransaction transaction = adminCaptureTransaction();

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_test_123"))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        20L,
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        when(paymentGateway.retrievePaymentFlow("pf_test_123"))
                .thenReturn(new PaymentFlowState(
                        "pf_test_123",
                        PaymentFlowStatus.PROCESSING,
                        null,
                        null));

        service.synchronize("pf_test_123");

        verify(resultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    private Payment authorizedPayment() {

        Payment payment = mock(Payment.class);
        Order order = mock(Order.class);

        when(payment.getId()).thenReturn(20L);
        when(payment.getOrder()).thenReturn(order);
        when(payment.getStatus()).thenReturn(PaymentStatus.AUTHORIZED);
        when(order.getId()).thenReturn(10L);

        return payment;
    }

    private PaymentTransaction adminCaptureTransaction() {

        PaymentTransaction transaction = mock(PaymentTransaction.class);

        when(transaction.getInitiatorType())
                .thenReturn(PaymentTransactionInitiatorType.ADMIN);
        when(transaction.getInitiatorId())
                .thenReturn(30L);
        when(transaction.getInitiatorUsername())
                .thenReturn("admin");
        when(transaction.getInternalNote())
                .thenReturn("発送処理");

        return transaction;
    }
}
