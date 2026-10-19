package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class PaymentCancellationStartServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentService paymentService;

    private PaymentCancellationStartService service;

    @BeforeEach
    void setUp() {

        service = new PaymentCancellationStartService(orderRepository, paymentService);
    }

    @Test
    void startAcceptsCancellableOrderWithinModificationPeriod() {

        Long orderId = 10L;
        Long userId = 20L;
        String username = "testuser";

        Order order = org.mockito.Mockito.mock(Order.class);

        PaymentCancellationStart expected = new PaymentCancellationStart(30L, 40L, "pf_test_123", "cancel-key-123", PaymentStartDisposition.NEW, new PaymentOperationSnapshot(10L, 30L, 40L, com.example.ecsite.entity.PaymentTransactionType.CANCEL, "cancel-key-123", 3300, 0, "pf_test_123", com.example.ecsite.entity.PaymentProvider.PAYJP, com.example.ecsite.entity.PaymentTransactionStatus.PENDING, com.example.ecsite.entity.PaymentTransactionInitiatorType.ADMIN, 30L, "admin", null));

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        when(order.canCancel())
                .thenReturn(true);

        when(paymentService.startCancellation(
                org.mockito.ArgumentMatchers.eq(order),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(expected);

        PaymentCancellationStart actual = service.start(
                orderId,
                userId,
                username);

        assertEquals(
                expected,
                actual);

        verify(paymentService)
                .startCancellation(
                        org.mockito.ArgumentMatchers.eq(order),
                        argThat(initiator -> initiator.type() == PaymentTransactionInitiatorType.USER
                                && userId.equals(initiator.id())
                                && username.equals(initiator.username())
                                && initiator.internalNote() == null));
    }

    @Test
    void startRejectsOrderThatCannotBeCancelled() {

        Long orderId = 10L;
        Long userId = 20L;

        Order order = org.mockito.Mockito.mock(Order.class);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        when(order.canCancel())
                .thenReturn(false);

        assertThrows(
                InvalidOrderStatusException.class,
                () -> service.start(
                        orderId,
                        userId,
                        "testuser"));

        verify(paymentService, never())
                .startCancellation(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void startDelegatesAfterDeadlineSoPaymentServiceCanReuseAcceptedCancellation() {
        Order order = org.mockito.Mockito.mock(Order.class);
        when(orderRepository.findByIdAndUserIdForUpdate(10L, 20L))
                .thenReturn(Optional.of(order));
        when(order.canCancel()).thenReturn(true);

        service.start(10L, 20L, "testuser");

        verify(paymentService).startCancellation(
                org.mockito.ArgumentMatchers.eq(order), org.mockito.ArgumentMatchers.any());
        verify(order, never()).isWithinModificationPeriod(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void startRejectsOrderOwnedByDifferentUser() {

        Long orderId = 10L;
        Long userId = 20L;

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> service.start(
                        orderId,
                        userId,
                        "testuser"));

        verify(paymentService, never())
                .startCancellation(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }
}
