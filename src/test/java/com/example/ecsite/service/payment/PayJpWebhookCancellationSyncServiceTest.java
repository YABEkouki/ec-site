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
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.PaymentFlowState;
import com.example.ecsite.payment.PaymentFlowStatus;
import com.example.ecsite.payment.PaymentGateway;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

class PayJpWebhookCancellationSyncServiceTest {

    private PaymentRepository paymentRepository;
    private PaymentTransactionRepository paymentTransactionRepository;
    private PaymentGateway paymentGateway;
    private PaymentCancellationResultService userResultService;
    private AdminPaymentCancellationResultService adminResultService;
    private PayJpWebhookCancellationSyncService service;

    @BeforeEach
    void setUp() {

        paymentRepository = mock(PaymentRepository.class);
        paymentTransactionRepository =
                mock(PaymentTransactionRepository.class);
        paymentGateway = mock(PaymentGateway.class);
        userResultService =
                mock(PaymentCancellationResultService.class);
        adminResultService =
                mock(AdminPaymentCancellationResultService.class);

        service = new PayJpWebhookCancellationSyncService(
                paymentRepository,
                paymentTransactionRepository,
                paymentGateway,
                userResultService,
                adminResultService);
    }

    @Test
    void canceledFlowCompletesUserCancellationWithSavedInitiator() {

        Payment payment = authorizedPayment();
        PaymentTransaction transaction =
                cancellationTransaction(
                        PaymentTransactionInitiatorType.USER,
                        30L,
                        "testuser",
                        null);

        preparePendingCancellation(payment, transaction);

        when(paymentGateway.retrievePaymentFlow("pf_test_123"))
                .thenReturn(canceledFlow());

        service.synchronize("pf_test_123");

        verify(userResultService)
                .apply(
                        10L,
                        20L,
                        30L,
                        "testuser",
                        new CancellationResult(
                                CancellationResultStatus.CANCELLED,
                                "pf_test_123"));

        verify(adminResultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void canceledFlowCompletesAdminCancellationWithSavedInitiator() {

        Payment payment = authorizedPayment();
        PaymentTransaction transaction =
                cancellationTransaction(
                        PaymentTransactionInitiatorType.ADMIN,
                        40L,
                        "admin",
                        "管理者キャンセル");

        preparePendingCancellation(payment, transaction);

        when(paymentGateway.retrievePaymentFlow("pf_test_123"))
                .thenReturn(canceledFlow());

        service.synchronize("pf_test_123");

        verify(adminResultService)
                .apply(
                        10L,
                        20L,
                        40L,
                        "admin",
                        "管理者キャンセル",
                        new CancellationResult(
                                CancellationResultStatus.CANCELLED,
                                "pf_test_123"));

        verify(userResultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
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
    void alreadyCancelledPaymentIgnoresDelayedWebhook() {

        Payment payment = mock(Payment.class);

        when(payment.getStatus())
                .thenReturn(PaymentStatus.CANCELLED);

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_test_123"))
                .thenReturn(Optional.of(payment));

        service.synchronize("pf_test_123");

        verify(paymentGateway, never())
                .retrievePaymentFlow("pf_test_123");
    }

    @Test
    void missingPendingCancellationDoesNotCallPayJp() {

        Payment payment = authorizedPayment();

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_test_123"))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        20L,
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.empty());

        service.synchronize("pf_test_123");

        verify(paymentGateway, never())
                .retrievePaymentFlow("pf_test_123");
    }

    @Test
    void unfinishedProviderFlowDoesNotCompleteCancellation() {

        Payment payment = authorizedPayment();
        PaymentTransaction transaction =
                cancellationTransaction(
                        PaymentTransactionInitiatorType.USER,
                        30L,
                        "testuser",
                        null);

        preparePendingCancellation(payment, transaction);

        when(paymentGateway.retrievePaymentFlow("pf_test_123"))
                .thenReturn(new PaymentFlowState(
                        "pf_test_123",
                        PaymentFlowStatus.PROCESSING,
                        null,
                        null));

        service.synchronize("pf_test_123");

        verify(userResultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());

        verify(adminResultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unsupportedInitiatorDoesNotCompleteCancellation() {

        Payment payment = authorizedPayment();
        PaymentTransaction transaction =
                cancellationTransaction(
                        PaymentTransactionInitiatorType.SYSTEM,
                        50L,
                        "system",
                        null);

        preparePendingCancellation(payment, transaction);

        when(paymentGateway.retrievePaymentFlow("pf_test_123"))
                .thenReturn(canceledFlow());

        service.synchronize("pf_test_123");

        verify(userResultService, never())
                .apply(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());

        verify(adminResultService, never())
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

    private PaymentTransaction cancellationTransaction(
            PaymentTransactionInitiatorType type,
            Long id,
            String username,
            String internalNote) {

        PaymentTransaction transaction =
                mock(PaymentTransaction.class);

        when(transaction.getInitiatorType()).thenReturn(type);
        when(transaction.getInitiatorId()).thenReturn(id);
        when(transaction.getInitiatorUsername()).thenReturn(username);
        when(transaction.getInternalNote()).thenReturn(internalNote);

        return transaction;
    }

    private void preparePendingCancellation(
            Payment payment,
            PaymentTransaction transaction) {

        when(paymentRepository.findByProviderAndProviderPaymentId(
                PaymentProvider.PAYJP,
                "pf_test_123"))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        20L,
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));
    }

    private PaymentFlowState canceledFlow() {

        return new PaymentFlowState(
                "pf_test_123",
                PaymentFlowStatus.CANCELED,
                null,
                null);
    }
}
