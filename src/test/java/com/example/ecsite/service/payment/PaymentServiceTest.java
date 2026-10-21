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
import com.example.ecsite.entity.PaymentTransactionInitiator;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
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
    private CaptureShipmentOperations captureOperations;
    private com.example.ecsite.service.OrderModificationGuard orderModificationGuard;

    @BeforeEach
    void setUp() {

        captureOperations = mock(CaptureShipmentOperations.class);
        orderModificationGuard = mock(com.example.ecsite.service.OrderModificationGuard.class);
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
                new PaymentConsistencyEvaluator(),
                orderModificationGuard,
                captureOperations,
                mock(OrderPaymentGuard.class),
                clock);
    }

    @Test
    void startAuthorizationCreatesPendingPaymentAndTransaction() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);

        when(order.getTotalAmount()).thenReturn(12_345);
        when(order.getContentRevision()).thenReturn(2);

        when(order.getId()).thenReturn(100L);

        when(paymentRepository
                .findByOrderIdForUpdate(100L))
                .thenReturn(List.of());

        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment saved = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(saved,"id",10L);
            return saved;
        });
        PaymentAuthorizationStart result = paymentService.startAuthorization(order);

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
        assertEquals(PaymentStartDisposition.NEW, result.disposition());
    }

    @Test
    void startAuthorizationReusesExistingPendingPaymentAndTransaction() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);

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
                        2026, 10, 1, 9, 30), TEST_INITIATOR);

        when(paymentRepository
                .findByOrderIdForUpdate(100L))
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
        assertEquals(PaymentStartDisposition.REUSED, result.disposition());
    }

    @Test
    void startAuthorizationRejectsMultiplePendingPayments() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);

        when(order.getId()).thenReturn(100L);

        Payment payment1 = createPayment(order);
        Payment payment2 = createPayment(order);

        when(paymentRepository
                .findByOrderIdForUpdate(100L))
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
        when(order.getActivePaymentId()).thenReturn(null);

        when(order.getId()).thenReturn(100L);

        Payment payment = createPayment(order);

        when(paymentRepository
                .findByOrderIdForUpdate(100L))
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
        when(order.getActivePaymentId()).thenReturn(null);
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
        when(order.getActivePaymentId()).thenReturn(null);
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
        when(order.getActivePaymentId()).thenReturn(null);

        Payment payment = new Payment(
                order,
                PaymentProvider.PAYJP,
                PaymentMethod.CARD,
                12_345,
                LocalDateTime.of(2026, 10, 1, 9, 0));

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        paymentService.setProviderPaymentId(order, assignmentSnapshot(payment), "pf_test_123");

        assertEquals(
                "pf_test_123",
                payment.getProviderPaymentId());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                payment.getUpdatedAt());
    }



    @Test
    void pendingAuthorizationResultDoesNotChangePaymentOrTransaction() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        Payment payment = createPayment(order);
        PaymentTransaction transaction = createAuthorizationTransaction(payment);

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyAuthorizationResult(order, resultSnapshot(payment, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE), new AuthorizationResult(
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
    void requiresPaymentMethodDoesNotFailPaymentOrTransaction() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        Payment payment = createPayment(order);
        PaymentTransaction transaction = createAuthorizationTransaction(payment);

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyAuthorizationResult(order, resultSnapshot(payment, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE), new AuthorizationResult(
                        AuthorizationResultStatus.REQUIRES_PAYMENT_METHOD,
                        null,
                        "card_declined",
                        "Card was declined"));

        assertEquals(
                PaymentStatus.PENDING,
                payment.getStatus());

        assertEquals(
                PaymentTransactionStatus.PENDING,
                transaction.getStatus());

        assertEquals(
                LocalDateTime.of(2026, 10, 1, 9, 0),
                payment.getUpdatedAt());

        assertNull(transaction.getCompletedAt());
    }

    @Test
    void requiresConfirmationDoesNotChangePaymentOrTransaction() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        Payment payment = createPayment(order);
        PaymentTransaction transaction = createAuthorizationTransaction(payment);

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyAuthorizationResult(order, resultSnapshot(payment, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE), new AuthorizationResult(
                        AuthorizationResultStatus.REQUIRES_CONFIRMATION,
                        null,
                        null,
                        null));

        assertEquals(
                PaymentStatus.PENDING,
                payment.getStatus());

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
        when(order.getActivePaymentId()).thenReturn(null);
        Payment payment = createPayment(order);
        PaymentTransaction transaction = createAuthorizationTransaction(payment);

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyAuthorizationResult(order, resultSnapshot(payment, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE), new AuthorizationResult(
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
        assertEquals(PaymentResultApplication.ALREADY_APPLIED,
                paymentService.applyAuthorizationResult(order,
                        resultSnapshot(payment, PaymentTransactionType.AUTHORIZE),
                        new AuthorizationResult(AuthorizationResultStatus.REQUIRES_ACTION, null, null, null)));
    }

    @Test
    void authorizedResultCompletesPaymentAndTransaction() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        Payment payment = createPayment(order);
        PaymentTransaction transaction = createAuthorizationTransaction(payment);

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        var snapshot = resultSnapshot(payment, PaymentTransactionType.AUTHORIZE);
        var result = new AuthorizationResult(AuthorizationResultStatus.AUTHORIZED, snapshot.providerPaymentId(), null, null);
        assertEquals(PaymentResultApplication.APPLIED, paymentService.applyAuthorizationResult(order, snapshot, result));

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
        assertEquals(snapshot.providerPaymentId(), transaction.getProviderTransactionId());
        var completedAt = transaction.getCompletedAt();
        var updatedAt = payment.getUpdatedAt();
        assertEquals(PaymentResultApplication.ALREADY_APPLIED, paymentService.applyAuthorizationResult(order, snapshot, result));
        assertEquals(PaymentResultApplication.IGNORED_STALE, paymentService.applyAuthorizationResult(order, snapshot,
                new AuthorizationResult(AuthorizationResultStatus.PENDING, null, null, null)));
        assertEquals(PaymentTransactionStatus.SUCCEEDED, transaction.getStatus());
        assertEquals(completedAt, transaction.getCompletedAt());
        assertEquals(updatedAt, payment.getUpdatedAt());
        assertEquals(snapshot.providerPaymentId(), transaction.getProviderTransactionId());
        assertEquals(1, paymentTransactionRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId()).size());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void failedResultFailsPaymentAndTransaction() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        Payment payment = createPayment(order);
        PaymentTransaction transaction = createAuthorizationTransaction(payment);

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyAuthorizationResult(order, resultSnapshot(payment, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE), new AuthorizationResult(
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
        when(order.getActivePaymentId()).thenReturn(null);
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
        when(order.getActivePaymentId()).thenReturn(null);
        Payment payment = createPayment(order);

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 30));

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        paymentService.applyAuthorizationResult(order, resultSnapshot(payment, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE), new AuthorizationResult(
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
        when(order.getActivePaymentId()).thenReturn(null);
        Payment payment = createPayment(order);

        payment.markFailed(
                LocalDateTime.of(2026, 10, 1, 9, 30));

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        paymentService.applyAuthorizationResult(order, resultSnapshot(payment, com.example.ecsite.entity.PaymentTransactionType.AUTHORIZE), new AuthorizationResult(
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
        when(order.getActivePaymentId()).thenReturn(null);

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
        when(order.getActivePaymentId()).thenReturn(null);

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
        when(order.getActivePaymentId()).thenReturn(null);

        when(order.getId()).thenReturn(100L);
        when(order.getContentRevision()).thenReturn(3);

        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 1, 9, 10));

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        when(paymentRepository
                .findByOrderIdForUpdate(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.empty());

        LocalDateTime databaseNow = LocalDateTime.of(2026, 10, 1, 10, 7);
        when(orderModificationGuard.requireAllowed(order)).thenReturn(databaseNow);

        PaymentCancellationStart result = paymentService.startCancellation(
                order,
                TEST_INITIATOR);

        ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(
                PaymentTransaction.class);

        verify(paymentTransactionRepository)
                .save(captor.capture());

        PaymentTransaction transaction = captor.getValue();
        assertEquals(databaseNow, transaction.getCreatedAt());
        var sequence = org.mockito.Mockito.inOrder(paymentRepository, paymentTransactionRepository, orderModificationGuard);
        sequence.verify(paymentRepository).findByOrderIdForUpdate(100L);
        sequence.verify(paymentTransactionRepository).findByPaymentIdAndTransactionTypeAndStatus(
                payment.getId(), PaymentTransactionType.CANCEL, PaymentTransactionStatus.PENDING);
        sequence.verify(orderModificationGuard).requireAllowed(order);
        sequence.verify(paymentTransactionRepository).save(transaction);

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
                PaymentTransactionInitiatorType.ADMIN,
                transaction.getInitiatorType());

        assertEquals(
                1L,
                transaction.getInitiatorId());

        assertEquals(
                "test-admin",
                transaction.getInitiatorUsername());

        assertEquals(
                "test",
                transaction.getInternalNote());

        assertEquals(
                "pf_test_123",
                result.providerPaymentId());

        assertEquals(
                transaction.getIdempotencyKey(),
                result.idempotencyKey());
        assertEquals(PaymentStartDisposition.NEW, result.disposition());
    }

    @Test
    void startCancellationReusesExistingPendingTransaction() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);

        when(order.getId()).thenReturn(100L);
        when(order.getContentRevision()).thenReturn(2);

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
                        2026, 10, 1, 9, 30),
                new PaymentTransactionInitiator(PaymentTransactionInitiatorType.USER, 99L, "original-user", "original-note"));

        when(paymentRepository
                .findByOrderIdForUpdate(100L))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        PaymentCancellationStart result = paymentService.startCancellation(
                order,
                TEST_INITIATOR);

        assertEquals(
                "pf_test_123",
                result.providerPaymentId());

        assertEquals(
                "cancel-key-123",
                result.idempotencyKey());

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
        assertEquals(PaymentStartDisposition.REUSED, result.disposition());
        assertEquals(PaymentOperationSnapshot.from(transaction), result.snapshot());
        assertEquals(99L, result.snapshot().initiatorId());
        assertEquals("original-user", result.snapshot().initiatorUsername());
        assertEquals("original-note", result.snapshot().internalNote());
        org.mockito.Mockito.verifyNoInteractions(orderModificationGuard);
    }

    @Test
    void startCancellationRejectsClosedModificationWindowWithoutSavingTransaction() {
        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        when(order.getId()).thenReturn(100L);
        Payment payment = createPayment(order);
        payment.setProviderPaymentId("pf_test_123", LocalDateTime.of(2026, 10, 1, 9, 10));
        payment.markAuthorized(LocalDateTime.of(2026, 10, 1, 9, 20));
        when(paymentRepository.findByOrderIdForUpdate(100L)).thenReturn(List.of(payment));
        var failure = new com.example.ecsite.exception.InvalidOrderStatusException("この注文の変更受付は終了しています。");
        when(orderModificationGuard.requireAllowed(order)).thenThrow(failure);

        org.junit.jupiter.api.Assertions.assertSame(failure, assertThrows(com.example.ecsite.exception.InvalidOrderStatusException.class,
                () -> paymentService.startCancellation(order, TEST_INITIATOR)));
        verify(paymentTransactionRepository, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void startCancellationRejectsOrderWithoutAuthorizedPayment() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);

        when(order.getId()).thenReturn(100L);

        Payment payment = createPayment(order);

        when(paymentRepository
                .findByOrderIdForUpdate(100L))
                .thenReturn(List.of(payment));

        assertThrows(
                IllegalStateException.class,
                () -> paymentService.startCancellation(
                        order,
                        TEST_INITIATOR));

        verify(paymentTransactionRepository, never())
                .save(any(PaymentTransaction.class));
    }

    @Test
    void cancellationResultCancelsPaymentAndCompletesTransaction() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);

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
                        2026, 10, 1, 9, 30), TEST_INITIATOR);

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyCancellationResult(order, resultSnapshot(payment, com.example.ecsite.entity.PaymentTransactionType.CANCEL), new CancellationResult(
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
        when(order.getActivePaymentId()).thenReturn(null);

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
                        2026, 10, 1, 9, 30), TEST_INITIATOR);

        when(paymentRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        10L,
                        PaymentTransactionType.CANCEL,
                        PaymentTransactionStatus.PENDING))
                .thenReturn(Optional.of(transaction));

        paymentService.applyCancellationResult(order, resultSnapshot(payment, com.example.ecsite.entity.PaymentTransactionType.CANCEL), new CancellationResult(
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
    void canResumeAuthorizationReturnsFalseForProviderlessPendingPayJpCardAuthorization() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
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

        assertFalse(
                paymentService.canResumeAuthorization(order));
    }

    @Test
    void canResumeAuthorizationReturnsFalseWhenPendingPaymentDoesNotExist() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
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
        when(order.getActivePaymentId()).thenReturn(null);

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
    void canResumeAuthorizationReturnsTrueWhenProviderPaymentIdExists() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        when(order.getId()).thenReturn(100L);
        when(order.getTotalAmount()).thenReturn(12_345);

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

        assertTrue(
                paymentService.canResumeAuthorization(order));
    }

    @Test
    void canRecoverAuthorizationReturnsTrueWhenProviderPaymentIdExists() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        when(order.getId()).thenReturn(100L);
        when(order.getTotalAmount()).thenReturn(12_345);

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

        assertTrue(
                paymentService.canRecoverAuthorization(order));
    }

    @Test
    void getRecoverableAuthorizationPaymentIdReturnsExistingPaymentId() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        when(order.getId()).thenReturn(100L);
        when(order.getTotalAmount()).thenReturn(12_345);

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

        assertEquals(
                payment.getId(),
                paymentService.getRecoverableAuthorizationPaymentId(order));
    }

    @Test
    void canRecoverAuthorizationReturnsFalseWhenPaymentAmountDoesNotMatchOrder() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        when(order.getId()).thenReturn(100L);
        when(order.getTotalAmount()).thenReturn(5000);

        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 2, 13, 0));

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        assertFalse(
                paymentService.canRecoverAuthorization(order));

        verify(paymentTransactionRepository, never())
                .findByPaymentIdAndTransactionTypeAndStatus(
                        anyLong(),
                        any(),
                        any());
    }

    @Test
    void canRecoverAuthorizationReturnsFalseWhenContentRevisionDoesNotMatch() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        when(order.getId()).thenReturn(100L);
        when(order.getTotalAmount()).thenReturn(12_345);
        when(order.getContentRevision()).thenReturn(3);

        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 2, 13, 0));

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
                paymentService.canRecoverAuthorization(order));
    }

    @Test
    void canRecoverAuthorizationReturnsTrueForRequiresActionPayment() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        when(order.getId()).thenReturn(100L);
        when(order.getTotalAmount()).thenReturn(12_345);

        Payment payment = createPayment(order);

        payment.setProviderPaymentId(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 2, 13, 0));

        payment.markRequiresAction(
                LocalDateTime.of(2026, 10, 2, 13, 1));

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
                paymentService.canRecoverAuthorization(order));
    }

    @Test
    void canRecoverAuthorizationReturnsFalseForRequiresActionWithoutProviderPaymentId() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);
        when(order.getId()).thenReturn(100L);
        when(order.getTotalAmount()).thenReturn(12_345);

        Payment payment = createPayment(order);

        payment.markRequiresAction(
                LocalDateTime.of(2026, 10, 2, 13, 1));

        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(100L))
                .thenReturn(List.of(payment));

        assertFalse(
                paymentService.canRecoverAuthorization(order));

        verify(paymentTransactionRepository, never())
                .findByPaymentIdAndTransactionTypeAndStatus(
                        anyLong(),
                        any(),
                        any());
    }

    @Test
    void canResumeAuthorizationReturnsFalseWhenPaymentAmountDoesNotMatchOrder() {

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);

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
        when(order.getActivePaymentId()).thenReturn(null);

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







    @Test
    void hasAuthorizedCardPaymentReturnsTrueRegardlessOfOrderConsistency() {

        Long orderId = 1L;

        Payment payment = mock(Payment.class);

        when(payment.getProvider())
                .thenReturn(PaymentProvider.PAYJP);
        when(payment.getPaymentMethod())
                .thenReturn(PaymentMethod.CARD);
        when(payment.getStatus())
                .thenReturn(PaymentStatus.AUTHORIZED);

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .thenReturn(List.of(payment));

        assertTrue(
                paymentService.hasAuthorizedCardPayment(orderId));
    }

    @Test
    void canCaptureForShipmentReturnsTrueWhenAuthorizationMatchesCurrentOrder() {

        Long orderId = 100L;

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);

        when(order.getTotalAmount()).thenReturn(12_345);
        when(order.getContentRevision()).thenReturn(2);

        Payment payment = createPayment(order);

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        PaymentTransaction authorization = createSucceededAuthorization(
                payment,
                12_345,
                2);

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.SUCCEEDED))
                .thenReturn(Optional.of(authorization));

        assertTrue(
                paymentService.canCaptureForShipment(orderId));
    }

    @Test
    void canCaptureForShipmentReturnsTrueWhenOrderAmountDecreasedAfterAuthorization() {

        Long orderId = 100L;

        Order order = mock(Order.class);
        when(order.getActivePaymentId()).thenReturn(null);

        when(order.getTotalAmount()).thenReturn(10_000);
        when(order.getContentRevision()).thenReturn(1);

        Payment payment = createPayment(order);

        payment.markAuthorized(
                LocalDateTime.of(2026, 10, 1, 9, 20));

        PaymentTransaction authorization = createSucceededAuthorization(
                payment,
                12_345,
                0);

        when(paymentRepository
                .findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .thenReturn(List.of(payment));

        when(paymentTransactionRepository
                .findByPaymentIdAndTransactionTypeAndStatus(
                        payment.getId(),
                        PaymentTransactionType.AUTHORIZE,
                        PaymentTransactionStatus.SUCCEEDED))
                .thenReturn(Optional.of(authorization));

        assertTrue(
                paymentService.canCaptureForShipment(orderId));
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

    private PaymentTransaction createSucceededAuthorization(
            Payment payment,
            int amount,
            int contentRevision) {

        PaymentTransaction transaction = new PaymentTransaction(
                payment,
                PaymentTransactionType.AUTHORIZE,
                amount,
                contentRevision,
                "authorize-key-123",
                LocalDateTime.of(2026, 10, 1, 9, 0));

        transaction.markSucceeded(
                "pf_test_123",
                LocalDateTime.of(2026, 10, 1, 9, 20));

        return transaction;
    }

    private static final PaymentTransactionInitiator TEST_INITIATOR = new PaymentTransactionInitiator(
            PaymentTransactionInitiatorType.ADMIN,
            1L,
            "test-admin",
            "test");


    private PaymentOperationSnapshot resultSnapshot(Payment payment, PaymentTransactionType type) {
        Order order = payment.getOrder();
        org.springframework.test.util.ReflectionTestUtils.setField(payment,"id",10L);
        when(order.getId()).thenReturn(1L);
        PaymentTransaction transaction = paymentTransactionRepository.findByPaymentIdAndTransactionTypeAndStatus(
                10L,type,PaymentTransactionStatus.PENDING).orElseGet(() -> type==PaymentTransactionType.CANCEL
                    ? new PaymentTransaction(payment,type,12345,2,"fixture-key",LocalDateTime.of(2026,10,1,9,0),TEST_INITIATOR)
                    : new PaymentTransaction(payment,type,12345,2,"fixture-key",LocalDateTime.of(2026,10,1,9,0)));
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"id",100L);
        when(paymentRepository.findByOrderIdForUpdate(1L)).thenReturn(List.of(payment));
        when(paymentTransactionRepository.findById(100L)).thenReturn(Optional.of(transaction));
        when(paymentTransactionRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(10L)).thenReturn(java.util.List.of(transaction));
        when(order.getTotalAmount()).thenReturn(transaction.getAmount());
        when(order.getContentRevision()).thenReturn(transaction.getOrderContentRevision());
        when(order.getStatus()).thenReturn(com.example.ecsite.entity.OrderStatus.ORDERED);
        if(payment.getProviderPaymentId()==null) payment.setProviderPaymentId("pf_test_123",payment.getUpdatedAt());
        if(payment.getStatus()==PaymentStatus.AUTHORIZED && type==PaymentTransactionType.AUTHORIZE) transaction.markSucceeded(null,payment.getUpdatedAt());
        if(payment.getStatus()==PaymentStatus.FAILED) {
            transaction.markFailed(null,"card_declined","Card was declined",payment.getUpdatedAt());
            when(order.getStatus()).thenReturn(com.example.ecsite.entity.OrderStatus.CANCELLED);
        }
        if(type==PaymentTransactionType.CANCEL) {
            var authorization=new PaymentTransaction(payment,PaymentTransactionType.AUTHORIZE,payment.getAmount(),
                    transaction.getOrderContentRevision(),"successful-authorization",transaction.getCreatedAt());
            org.springframework.test.util.ReflectionTestUtils.setField(authorization,"id",99L);
            authorization.markSucceeded(payment.getProviderPaymentId(),transaction.getCreatedAt());
            when(paymentTransactionRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(10L)).thenReturn(List.of(authorization,transaction));
        }
        org.mockito.Mockito.clearInvocations(paymentTransactionRepository);
        return PaymentOperationSnapshot.from(transaction);
    }
    private PaymentOperationSnapshot assignmentSnapshot(Payment payment) {
        var snapshot = resultSnapshot(payment, PaymentTransactionType.AUTHORIZE);
        org.springframework.test.util.ReflectionTestUtils.setField(payment,"providerPaymentId",null);
        return new PaymentOperationSnapshot(snapshot.orderId(),snapshot.paymentId(),snapshot.transactionId(),snapshot.type(),snapshot.idempotencyKey(),snapshot.amount(),snapshot.orderContentRevision(),null,snapshot.provider(),snapshot.status(),snapshot.initiatorType(),snapshot.initiatorId(),snapshot.initiatorUsername(),snapshot.internalNote());
    }
    @Test void providerlessPendingRequiresConfirmation() {
        Order order=mock(Order.class);when(order.getId()).thenReturn(100L);
        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(100L)).thenReturn(List.of(createPayment(order)));
        assertTrue(paymentService.requiresAuthorizationConfirmation(order));
    }
    @Test void providerKnownDoesNotRequireMissingIdConfirmation() {
        Order order=mock(Order.class);when(order.getId()).thenReturn(100L);
        Payment payment=createPayment(order);payment.setProviderPaymentId("pf",LocalDateTime.of(2026,10,1,10,0));
        when(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(100L)).thenReturn(List.of(payment));
        assertFalse(paymentService.requiresAuthorizationConfirmation(order));
    }

    @Test void captureStartDelegatesToProtectedShipmentOperation() {
        Order order=mock(Order.class);when(order.getId()).thenReturn(100L);
        paymentService.startCapture(order,TEST_INITIATOR);
        verify(captureOperations).start(100L,TEST_INITIATOR.id(),TEST_INITIATOR.username(),TEST_INITIATOR.internalNote());
        org.mockito.Mockito.verifyNoInteractions(paymentRepository,paymentTransactionRepository);
    }
    @Test void captureResultDelegatesOnlyForMatchingOrder() {
        Order order=mock(Order.class);when(order.getId()).thenReturn(100L);
        var snapshot=PaymentSnapshotFixtures.of(100L,20L,30L,PaymentTransactionType.CAPTURE,"key",200,0,"provider",PaymentTransactionInitiatorType.ADMIN,9L,"admin",null);
        var result=new CaptureResult(CaptureResultStatus.CAPTURED,"provider",null,null);
        when(captureOperations.apply(snapshot,result)).thenReturn(PaymentResultApplication.APPLIED);
        assertEquals(PaymentResultApplication.APPLIED,paymentService.applyCaptureResult(order,snapshot,result));
        when(order.getId()).thenReturn(999L);
        assertEquals(PaymentResultApplication.CONFLICT,paymentService.applyCaptureResult(order,snapshot,result));
        verify(captureOperations).apply(snapshot,result);
    }
    @Test void futureAuthorizationFailureCannotMutateOriginalPayment() {
        Order order=mock(Order.class);when(order.getActivePaymentId()).thenReturn(null);
        Payment payment=createPayment(order);var snapshot=resultSnapshot(payment,PaymentTransactionType.AUTHORIZE);
        var operation=paymentTransactionRepository.findById(snapshot.transactionId()).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(operation,"purpose",com.example.ecsite.entity.PaymentTransactionPurpose.ORDER_CHANGE_AUTHORIZATION);
        var future=PaymentOperationSnapshot.from(operation);
        assertEquals(PaymentResultApplication.CONFLICT,paymentService.applyAuthorizationResult(order,future,
                new AuthorizationResult(AuthorizationResultStatus.FAILED,future.providerPaymentId(),null,null)));
        assertEquals(PaymentStatus.PENDING,payment.getStatus());
        assertEquals(PaymentTransactionStatus.PENDING,operation.getStatus());
        verify(order,never()).clearActivePaymentAfterTermination(anyLong());
    }
    @Test void futureOldAuthorizationCancellationCannotMutateOriginalPayment() {
        Order order=mock(Order.class);when(order.getActivePaymentId()).thenReturn(null);
        Payment payment=createPayment(order);payment.markAuthorized(LocalDateTime.of(2026,10,1,9,1));
        var snapshot=resultSnapshot(payment,PaymentTransactionType.CANCEL);
        var operation=paymentTransactionRepository.findById(snapshot.transactionId()).orElseThrow();
        assertEquals(PaymentResultApplication.APPLIED,paymentService.applyCancellationResult(order,snapshot,
                new CancellationResult(CancellationResultStatus.PENDING,snapshot.providerPaymentId())));
        org.springframework.test.util.ReflectionTestUtils.setField(operation,"purpose",com.example.ecsite.entity.PaymentTransactionPurpose.ORDER_CHANGE_OLD_AUTH_CANCEL);
        assertEquals(PaymentResultApplication.CONFLICT,paymentService.applyCancellationResult(order,PaymentOperationSnapshot.from(operation),
                new CancellationResult(CancellationResultStatus.CANCELLED,snapshot.providerPaymentId())));
        assertEquals(PaymentStatus.AUTHORIZED,payment.getStatus());
        assertEquals(PaymentTransactionStatus.PENDING,operation.getStatus());
        verify(order,never()).clearActivePaymentAfterTermination(anyLong());
    }
}
