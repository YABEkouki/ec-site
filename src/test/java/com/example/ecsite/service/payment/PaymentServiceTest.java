package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.Payment;
import com.example.ecsite.entity.PaymentMethod;
import com.example.ecsite.entity.PaymentProvider;
import com.example.ecsite.entity.PaymentStatus;
import com.example.ecsite.entity.PaymentTransaction;
import com.example.ecsite.entity.PaymentTransactionStatus;
import com.example.ecsite.entity.PaymentTransactionType;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.repository.PaymentRepository;
import com.example.ecsite.repository.PaymentTransactionRepository;

class PaymentServiceTest {

    private PaymentRepository paymentRepository;
    private PaymentTransactionRepository paymentTransactionRepository;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {

        paymentRepository = mock(PaymentRepository.class);
        paymentTransactionRepository = mock(PaymentTransactionRepository.class);

        Clock clock = Clock.fixed(
                LocalDateTime.of(2026, 10, 1, 10, 0)
                        .atZone(ZoneId.of("Asia/Tokyo"))
                        .toInstant(),
                ZoneId.of("Asia/Tokyo"));

        paymentService = new PaymentService(
                paymentRepository,
                paymentTransactionRepository,
                clock);
    }

    @Test
    void startAuthorizationCreatesPendingPaymentAndTransaction() {

        Order order = mock(Order.class);

        when(order.getTotalAmount()).thenReturn(12_345);
        when(order.getContentRevision()).thenReturn(2);

        paymentService.startAuthorization(order);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);

        verify(paymentRepository)
                .save(paymentCaptor.capture());

        Payment payment = paymentCaptor.getValue();

        assertEquals(order, payment.getOrder());
        assertEquals(PaymentProvider.PAYJP, payment.getProvider());
        assertEquals(PaymentMethod.CARD, payment.getPaymentMethod());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(12_345, payment.getAmount());

        ArgumentCaptor<PaymentTransaction> transactionCaptor = ArgumentCaptor.forClass(PaymentTransaction.class);

        verify(paymentTransactionRepository)
                .save(transactionCaptor.capture());

        PaymentTransaction transaction = transactionCaptor.getValue();

        assertEquals(payment, transaction.getPayment());
        assertEquals(
                PaymentTransactionType.AUTHORIZE,
                transaction.getTransactionType());
        assertEquals(
                PaymentTransactionStatus.PENDING,
                transaction.getStatus());
        assertEquals(12_345, transaction.getAmount());
        assertEquals(2, transaction.getOrderContentRevision());

        assertNotNull(transaction.getIdempotencyKey());
        assertEquals(
                transaction.getIdempotencyKey().trim(),
                transaction.getIdempotencyKey());
    }

    @Test
    void setProviderPaymentIdUpdatesPayment() {

        Order order = mock(Order.class);

        Payment payment = new Payment(
                order,
                PaymentProvider.PAYJP,
                PaymentMethod.CARD,
                12_345,
                LocalDateTime.of(2026, 10, 1, 9, 0));

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        paymentService.setProviderPaymentId(
                10L,
                "pf_test_123");

        assertEquals(
                "pf_test_123",
                payment.getProviderPaymentId());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                payment.getUpdatedAt());
    }

    @Test
    void setProviderPaymentIdRejectsBlankProviderPaymentId() {

        assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.setProviderPaymentId(
                        10L,
                        " "));

        verify(paymentRepository, never())
                .findById(any());
    }

    @Test
    void setProviderPaymentIdRejectsUnknownPayment() {

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.setProviderPaymentId(
                        10L,
                        "pf_test_123"));
    }

    @Test
    void pendingAuthorizationResultDoesNotChangePaymentOrTransaction() {

        Order order = mock(Order.class);
        Payment payment = createPayment(order);
        PaymentTransaction transaction = createAuthorizationTransaction(payment);

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyAuthorizationResult(
                10L,
                new AuthorizationResult(
                        AuthorizationResultStatus.PENDING,
                        null,
                        null,
                        null));

        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(
                PaymentTransactionStatus.PENDING,
                transaction.getStatus());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 9, 0),
                payment.getUpdatedAt());

        assertNull(transaction.getCompletedAt());
    }

    @Test
    void requiresActionUpdatesPaymentButLeavesTransactionPending() {

        Order order = mock(Order.class);
        Payment payment = createPayment(order);
        PaymentTransaction transaction = createAuthorizationTransaction(payment);

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyAuthorizationResult(
                10L,
                new AuthorizationResult(
                        AuthorizationResultStatus.REQUIRES_ACTION,
                        null,
                        null,
                        null));

        assertEquals(
                PaymentStatus.REQUIRES_ACTION,
                payment.getStatus());

        assertEquals(
                PaymentTransactionStatus.PENDING,
                transaction.getStatus());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                payment.getUpdatedAt());

        assertNull(transaction.getCompletedAt());
    }

    @Test
    void authorizedResultCompletesPaymentAndTransaction() {

        Order order = mock(Order.class);
        Payment payment = createPayment(order);
        PaymentTransaction transaction = createAuthorizationTransaction(payment);

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyAuthorizationResult(
                10L,
                new AuthorizationResult(
                        AuthorizationResultStatus.AUTHORIZED,
                        null,
                        null,
                        null));

        assertEquals(
                PaymentStatus.AUTHORIZED,
                payment.getStatus());

        assertEquals(
                PaymentTransactionStatus.SUCCEEDED,
                transaction.getStatus());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                payment.getUpdatedAt());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                transaction.getCompletedAt());
    }

    @Test
    void failedResultFailsPaymentAndTransaction() {

        Order order = mock(Order.class);
        Payment payment = createPayment(order);
        PaymentTransaction transaction = createAuthorizationTransaction(payment);

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyAuthorizationResult(
                10L,
                new AuthorizationResult(
                        AuthorizationResultStatus.FAILED,
                        null,
                        "card_declined",
                        "Card was declined"));

        assertEquals(PaymentStatus.FAILED, payment.getStatus());

        assertEquals(
                PaymentTransactionStatus.FAILED,
                transaction.getStatus());

        assertEquals(
                "card_declined",
                transaction.getFailureCode());

        assertEquals(
                "Card was declined",
                transaction.getFailureMessage());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                payment.getUpdatedAt());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                transaction.getCompletedAt());
    }

    @Test
    void getProviderPaymentIdReturnsStoredId() {

        Order order = mock(Order.class);
        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 1, 9, 30));

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        assertEquals(
                "pf_test_123",
                paymentService.getProviderPaymentId(10L));
    }

    @Test
    void repeatedAuthorizedResultDoesNothing() {

        Order order = mock(Order.class);
        Payment payment = createPayment(order);

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 30));

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        paymentService.applyAuthorizationResult(
                10L,
                new AuthorizationResult(
                        AuthorizationResultStatus.AUTHORIZED,
                        null,
                        null,
                        null));

        assertEquals(
                PaymentStatus.AUTHORIZED,
                payment.getStatus());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 9, 30),
                payment.getUpdatedAt());

        verify(
                paymentTransactionRepository,
                never())
                .findByPaymentIdAndTransactionTypeAndStatus(
                        anyLong(),
                        any(),
                        any());
    }

    @Test
    void repeatedFailedResultDoesNothing() {

        Order order = mock(Order.class);
        Payment payment = createPayment(order);

        payment.markFailed(
                LocalDateTime.of(2026, 10, 1, 9, 30));

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        paymentService.applyAuthorizationResult(
                10L,
                new AuthorizationResult(
                        AuthorizationResultStatus.FAILED,
                        null,
                        "card_declined",
                        "Card was declined"));

        assertEquals(
                PaymentStatus.FAILED,
                payment.getStatus());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 9, 30),
                payment.getUpdatedAt());

        verify(
                paymentTransactionRepository,
                never())
                .findByPaymentIdAndTransactionTypeAndStatus(
                        anyLong(),
                        any(),
                        any());
    }

    private Payment createPayment(Order order) {
        return new Payment(
                order,
                PaymentProvider.PAYJP,
                PaymentMethod.CARD,
                12_345,
                LocalDateTime.of(2026, 10, 1, 9, 0));
    }

    private PaymentTransaction createAuthorizationTransaction(
            Payment payment) {

        return new PaymentTransaction(
                payment,
                PaymentTransactionType.AUTHORIZE,
                12_345,
                2,
                "idempotency-key-123",
                LocalDateTime.of(2026, 10, 1, 9, 0));
    }

}
