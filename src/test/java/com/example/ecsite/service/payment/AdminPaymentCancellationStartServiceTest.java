package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.repository.OrderRepository;

class AdminPaymentCancellationStartServiceTest {

    private OrderRepository orderRepository;
    private PaymentService paymentService;
    private AdminPaymentCancellationStartService service;

    @BeforeEach
    void setUp() {

        orderRepository = mock(OrderRepository.class);
        paymentService = mock(PaymentService.class);

        service = new AdminPaymentCancellationStartService(
                orderRepository,
                paymentService);
    }

    @Test
    void cancellableOrderStartsCancellationAfterLockingOrder() {

        Long orderId = 1L;

        Order order = mock(Order.class);
        PaymentCancellationStart expected = mock(PaymentCancellationStart.class);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        when(order.canCancel())
                .thenReturn(true);

        when(paymentService.startCancellation(order))
                .thenReturn(expected);

        PaymentCancellationStart actual = service.start(orderId);

        assertSame(expected, actual);

        verify(orderRepository)
                .findByIdForUpdate(orderId);

        verify(paymentService)
                .startCancellation(order);
    }

    @Test
    void nonCancellableOrderDoesNotStartCancellation() {

        Long orderId = 1L;

        Order order = mock(Order.class);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        when(order.canCancel())
                .thenReturn(false);

        assertThrows(
                InvalidOrderStatusException.class,
                () -> service.start(orderId));

        verify(paymentService, never())
                .startCancellation(order);
    }
}
