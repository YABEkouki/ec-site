package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
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

        when(order.getId()).thenReturn(100L);

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of());

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
    void startAuthorizationReusesExistingPendingPaymentAndTransaction() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);

        Payment payment = createPayment(order);
        when(order.getTotalAmount()).thenReturn(12_345);
        when(order.getContentRevision()).thenReturn(2);

        createPayment(order);

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.AUTHORIZE,
                12_345,
                2,
                "authorization-key-123",
                LocalDateTime.of(
                        2026, 10, 1, 9, 30));

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        PaymentAuthorizationStart result = paymentService.startAuthorization(order);

        assertEquals(
                payment.getId(),
                result.paymentId());

        assertEquals(
                transaction.getId(),
                result.transactionId());

        assertEquals(
                12_345,
                result.amount());

        assertEquals(
                "authorization-key-123",
                result.idempotencyKey());

        verify(paymentRepository, never())
                .save(any(Payment.class));

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
    }

    @Test
    void startAuthorizationRejectsMultiplePendingPayments() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);

        Payment payment1 = createPayment(order);
        Payment payment2 = createPayment(order);

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(
                        payment1,
                        payment2));

        assertThrows(
                IllegalStateException.class,
                () -> paymentService.startAuthorization(order));

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
    }

    @Test
    void startAuthorizationRejectsPendingPaymentWithoutPendingAuthorizationTransaction() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);

        Payment payment = createPayment(order);

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> paymentService.startAuthorization(order));

        verify(paymentRepository, never())
                .save(any(Payment.class));

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
    }

    @Test
    void findProviderPaymentIdReturnsStoredId() {

        Order order = mock(Order.class);
        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 1, 9, 30));

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        assertEquals(
                Optional.of("pf_test_123"),
                paymentService.findProviderPaymentId(10L));
    }

    @Test
    void findProviderPaymentIdReturnsEmptyWhenNotSet() {

        Order order = mock(Order.class);
        Payment payment = createPayment(order);

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        assertEquals(
                Optional.empty(),
                paymentService.findProviderPaymentId(10L));
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

    @Test
    void validatePaymentBelongsToOrderAcceptsMatchingOrder() {

        Order order = mock(Order.class);

        when(order.getId())
                .thenReturn(100L);

        Payment payment = createPayment(order);

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        paymentService.validatePaymentBelongsToOrder(
                10L,
                100L);

        verify(paymentRepository)
                .findById(10L);
    }

    @Test
    void validatePaymentBelongsToOrderRejectsDifferentOrder() {

        Order order = mock(Order.class);

        when(order.getId())
                .thenReturn(100L);

        Payment payment = createPayment(order);

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.validatePaymentBelongsToOrder(
                        10L,
                        200L));
    }

    @Test
    void validatePaymentBelongsToOrderRejectsUnknownPayment() {

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.validatePaymentBelongsToOrder(
                        10L,
                        100L));
    }

    @Test
    void startCancellationCreatesPendingCancelTransaction() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);
        when(order.getContentRevision()).thenReturn(3);

        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 1, 9, 10));

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.empty());

        PaymentCancellationStart result = paymentService.startCancellation(order);

        ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(
                PaymentTransaction.class);

        verify(paymentTransactionRepository)
                .save(captor.capture());

        PaymentTransaction transaction = captor.getValue();

        assertEquals(
                PaymentTransactionType.CANCEL,
                transaction.getTransactionType());

        assertEquals(
                PaymentTransactionStatus.PENDING,
                transaction.getStatus());

        assertEquals(
                12_345,
                transaction.getAmount());

        assertEquals(
                3,
                transaction.getOrderContentRevision());

        assertNotNull(
                transaction.getIdempotencyKey());

        assertEquals(
                "pf_test_123",
                result.providerPaymentId());

        assertEquals(
                transaction.getIdempotencyKey(),
                result.idempotencyKey());
    }

    @Test
    void startCancellationReusesExistingPendingTransaction() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);

        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 1, 9, 10));

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.CANCEL,
                12_345,
                2,
                "cancel-key-123",
                LocalDateTime.of(
                        2026, 10, 1, 9, 30));

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        PaymentCancellationStart result = paymentService.startCancellation(order);

        assertEquals(
                "pf_test_123",
                result.providerPaymentId());

        assertEquals(
                "cancel-key-123",
                result.idempotencyKey());

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
    }

    @Test
    void startCancellationRejectsOrderWithoutAuthorizedPayment() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);

        Payment payment = createPayment(order);

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        assertThrows(
                IllegalStateException.class,
                () -> paymentService.startCancellation(order));

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
    }

    @Test
    void cancellationResultCancelsPaymentAndCompletesTransaction() {

        Order order = mock(Order.class);

        Payment payment = createPayment(order);

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.CANCEL,
                12_345,
                2,
                "cancel-key-123",
                LocalDateTime.of(
                        2026, 10, 1, 9, 30));

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyCancellationResult(
                10L,
                new CancellationResult(
                        CancellationResultStatus.CANCELLED,
                        "pf_test_123"));

        assertEquals(
                PaymentStatus.CANCELLED,
                payment.getStatus());

        assertEquals(
                PaymentTransactionStatus.SUCCEEDED,
                transaction.getStatus());

        assertEquals(
                "pf_test_123",
                transaction.getProviderTransactionId());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                transaction.getCompletedAt());
    }

    @Test
    void pendingCancellationResultLeavesStatesUnchanged() {

        Order order = mock(Order.class);

        Payment payment = createPayment(order);

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.CANCEL,
                12_345,
                2,
                "cancel-key-123",
                LocalDateTime.of(
                        2026, 10, 1, 9, 30));

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyCancellationResult(
                10L,
                new CancellationResult(
                        CancellationResultStatus.PENDING,
                        "pf_test_123"));

        assertEquals(
                PaymentStatus.AUTHORIZED,
                payment.getStatus());

        assertEquals(
                PaymentTransactionStatus.PENDING,
                transaction.getStatus());

        assertNull(
                transaction.getCompletedAt());
    }

    @Test
    void startCaptureCreatesPendingCaptureTransaction() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);
        when(order.getContentRevision()).thenReturn(3);

        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 1, 9, 10));

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.empty());

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.empty());

        PaymentCaptureStart result = paymentService.startCapture(order);

        ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(
                PaymentTransaction.class);

        verify(paymentTransactionRepository)
                .save(captor.capture());

        PaymentTransaction transaction = captor.getValue();

        assertEquals(
                PaymentTransactionType.CAPTURE,
                transaction.getTransactionType());

        assertEquals(
                PaymentTransactionStatus.PENDING,
                transaction.getStatus());

        assertEquals(
                12_345,
                transaction.getAmount());

        assertEquals(
                3,
                transaction.getOrderContentRevision());

        assertNotNull(
                transaction.getIdempotencyKey());

        assertEquals(
                "pf_test_123",
                result.providerPaymentId());

        assertEquals(
                transaction.getIdempotencyKey(),
                result.idempotencyKey());
    }

    @Test
    void startCaptureReusesExistingPendingTransaction() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);

        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 1, 9, 10));

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.CAPTURE,
                12_345,
                2,
                "capture-key-123",
                LocalDateTime.of(
                        2026, 10, 1, 9, 30));

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.empty());

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        PaymentCaptureStart result = paymentService.startCapture(order);

        assertEquals(
                "pf_test_123",
                result.providerPaymentId());

        assertEquals(
                "capture-key-123",
                result.idempotencyKey());

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
    }

    @Test
    void startCaptureRejectsPendingCancellation() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);

        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 1, 9, 10));

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        PaymentTransaction cancellation = new PaymentTransaction(
                payment,
                PaymentTransactionType.CANCEL,
                12_345,
                2,
                "cancel-key-123",
                LocalDateTime.of(
                        2026, 10, 1, 9, 30));

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(cancellation));

        assertThrows(
                IllegalStateException.class,
                () -> paymentService.startCapture(order));

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
    }

    @Test
    void startCaptureRejectsOrderWithoutAuthorizedPayment() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);

        Payment payment = createPayment(order);

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        assertThrows(
                IllegalStateException.class,
                () -> paymentService.startCapture(order));

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
    }

    @Test
    void startCaptureRejectsMultipleAuthorizedPayments() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);

        Payment payment1 = createPayment(order);
        Payment payment2 = createPayment(order);

        payment1.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        payment2.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 25));

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(
                        payment1,
                        payment2));

        assertThrows(
                IllegalStateException.class,
                () -> paymentService.startCapture(order));

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
    }

    @Test
    void capturedResultCapturesPaymentAndCompletesTransaction() {

        Order order = mock(Order.class);

        Payment payment = createPayment(order);

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.CAPTURE,
                12_345,
                2,
                "capture-key-123",
                LocalDateTime.of(
                        2026, 10, 1, 9, 30));

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyCaptureResult(
                10L,
                new CaptureResult(
                        CaptureResultStatus.CAPTURED,
                        "pf_test_123",
                        null,
                        null));

        assertEquals(
                PaymentStatus.CAPTURED,
                payment.getStatus());

        assertEquals(
                PaymentTransactionStatus.SUCCEEDED,
                transaction.getStatus());

        assertEquals(
                "pf_test_123",
                transaction.getProviderTransactionId());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                payment.getUpdatedAt());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                transaction.getCompletedAt());
    }

    @Test
    void pendingCaptureResultLeavesStatesUnchanged() {

        Order order = mock(Order.class);

        Payment payment = createPayment(order);

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.CAPTURE,
                12_345,
                2,
                "capture-key-123",
                LocalDateTime.of(
                        2026, 10, 1, 9, 30));

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyCaptureResult(
                10L,
                new CaptureResult(
                        CaptureResultStatus.PENDING,
                        "pf_test_123",
                        null,
                        null));

        assertEquals(
                PaymentStatus.AUTHORIZED,
                payment.getStatus());

        assertEquals(
                PaymentTransactionStatus.PENDING,
                transaction.getStatus());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 9, 20),
                payment.getUpdatedAt());

        assertNull(
                transaction.getCompletedAt());
    }

    @Test
    void failedCaptureResultFailsTransactionButKeepsPaymentAuthorized() {

        Order order = mock(Order.class);

        Payment payment = createPayment(order);

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.CAPTURE,
                12_345,
                2,
                "capture-key-123",
                LocalDateTime.of(
                        2026, 10, 1, 9, 30));

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.CAPTURE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyCaptureResult(
                10L,
                new CaptureResult(
                        CaptureResultStatus.FAILED,
                        "pf_test_123",
                        "capture_failed",
                        "Capture failed"));

        assertEquals(
                PaymentStatus.AUTHORIZED,
                payment.getStatus());

        assertEquals(
                PaymentTransactionStatus.FAILED,
                transaction.getStatus());

        assertEquals(
                "pf_test_123",
                transaction.getProviderTransactionId());

        assertEquals(
                "capture_failed",
                transaction.getFailureCode());

        assertEquals(
                "Capture failed",
                transaction.getFailureMessage());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 9, 20),
                payment.getUpdatedAt());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                transaction.getCompletedAt());
    }

    @Test
    void requiresAuthorizationCancellationReturnsTrueForAuthorizedPayJpCardPayment() {

        Long orderId = 1L;

        Payment payment = mock(Payment.class);

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);

        when(payment.getPaymentMethod())
                .thenReturn(PaymentMethod.CARD);

        when(payment.getStatus())
                .thenReturn(PaymentStatus.AUTHORIZED);

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .thenReturn(List.of(payment));

        assertTrue(
                paymentService.requiresAuthorizationCancellation(orderId));
    }

    @Test
    void requiresAuthorizationCancellationReturnsFalseWhenPaymentIsCaptured() {

        Long orderId = 1L;

        Payment payment = mock(Payment.class);

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);

        when(payment.getPaymentMethod())
                .thenReturn(PaymentMethod.CARD);

        when(payment.getStatus())
                .thenReturn(PaymentStatus.CAPTURED);

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .thenReturn(List.of(payment));

        assertFalse(
                paymentService.requiresAuthorizationCancellation(orderId));
    }

    @Test
    void requiresAuthorizationCancellationReturnsFalseWhenNoPaymentExists() {

        Long orderId = 1L;

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .thenReturn(List.of());

        assertFalse(
                paymentService.requiresAuthorizationCancellation(orderId));
    }

    @Test
    void canResumeAuthorizationReturnsTrueForPendingPayJpCardAuthorization() {

        Order order = mock(Order.class);
        when(order.getId()).thenReturn(100L);
        when(order.getTotalAmount()).thenReturn(12_345);

        Payment payment = createPayment(order);

        PaymentTransaction transaction = mock(PaymentTransaction.class);

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        assertTrue(
                paymentService.canResumeAuthorization(order));
    }

    @Test
    void canResumeAuthorizationReturnsFalseWhenPendingPaymentDoesNotExist() {

        Order order = mock(Order.class);
        when(order.getId()).thenReturn(100L);

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of());

        assertFalse(
                paymentService.canResumeAuthorization(order));

        verify(paymentTransactionRepository, never())
                .findByPaymentIdAndTransactionTypeAndStatus(
                        anyLong(),
                        any(),
                        any());
    }

    @Test
    void canResumeAuthorizationReturnsFalseWhenPendingAuthorizationTransactionDoesNotExist() {

        Order order = mock(Order.class);

        Payment payment = createPayment(order);

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.empty());

        assertFalse(
                paymentService.canResumeAuthorization(order));
    }

    @Test
    void canResumeAuthorizationReturnsFalseWhenProviderPaymentIdAlreadyExists() {

        Order order = mock(Order.class);
        when(order.getId()).thenReturn(100L);

        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 2, 13, 0));

        PaymentTransaction transaction = mock(PaymentTransaction.class);

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        assertFalse(
                paymentService.canResumeAuthorization(order));
    }

    @Test
    void canResumeAuthorizationReturnsFalseWhenPaymentAmountDoesNotMatchOrder() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);
        when(order.getTotalAmount()).thenReturn(5000);

        Payment payment = createPayment(order);

        PaymentTransaction transaction = mock(PaymentTransaction.class);

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        assertFalse(
                paymentService.canResumeAuthorization(order));
    }

    @Test
    void canResumeAuthorizationReturnsFalseWhenContentRevisionDoesNotMatch() {

        Order order = mock(Order.class);

        when(order.getId()).thenReturn(100L);
        when(order.getTotalAmount()).thenReturn(12_345);
        when(order.getContentRevision()).thenReturn(3);

        Payment payment = createPayment(order);

        PaymentTransaction transaction = mock(PaymentTransaction.class);

        when(transaction.getOrderContentRevision())
                .thenReturn(2);

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        assertFalse(
                paymentService.canResumeAuthorization(order));
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
