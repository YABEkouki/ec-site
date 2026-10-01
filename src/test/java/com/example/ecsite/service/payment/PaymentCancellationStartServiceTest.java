package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Order;
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

        Clock clock = Clock.fixed(
                LocalDateTime.of(2026, 10, 1, 10, 0)
                        .atZone(ZoneId.of("Asia/Tokyo"))
                        .toInstant(),
                ZoneId.of("Asia/Tokyo"));

        service = new PaymentCancellationStartService(
                orderRepository,
                paymentService,
                clock);
    }

    @Test
    void startAcceptsCancellableOrderWithinModificationPeriod() {

        Long orderId = 10L;
        Long userId = 20L;

        Order order = org.mockito.Mockito.mock(Order.class);

        PaymentCancellationStart expected = new PaymentCancellationStart(
                30L,
                40L,
                "pf_test_123",
                "cancel-key-123");

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        when(order.canCancel())
                .thenReturn(true);

        when(order.isWithinModificationPeriod(
                LocalDateTime.of(2026, 10, 1, 10, 0)))
                .thenReturn(true);

        when(paymentService.startCancellation(order))
                .thenReturn(expected);

        PaymentCancellationStart actual = service.start(
                orderId,
                userId);

        assertEquals(
                expected,
                actual);

        verify(paymentService)
                .startCancellation(order);
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
                        userId));

        verify(paymentService, never())
                .startCancellation(order);
    }

    @Test
    void startRejectsOrderAfterModificationDeadline() {

        Long orderId = 10L;
        Long userId = 20L;

        Order order = org.mockito.Mockito.mock(Order.class);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));

        when(order.canCancel())
                .thenReturn(true);

        when(order.isWithinModificationPeriod(
                LocalDateTime.of(2026, 10, 1, 10, 0)))
                .thenReturn(false);

        assertThrows(
                InvalidOrderStatusException.class,
                () -> service.start(
                        orderId,
                        userId));

        verify(paymentService, never())
                .startCancellation(order);
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
                        userId));

        verify(paymentService, never())
                .startCancellation(
                        org.mockito.ArgumentMatchers.any());
    }
}
