package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.repository.OrderRepository;

class PaymentCaptureStartServiceTest {

    private OrderRepository orderRepository;
    private PaymentService paymentService;
    private PaymentCaptureStartService service;

    @BeforeEach
    void setUp() {

        orderRepository = mock(OrderRepository.class);
        paymentService = mock(PaymentService.class);

        service = new PaymentCaptureStartService(
                orderRepository,
                paymentService);
    }

    @Test
    void orderedOrderStartsCaptureAfterLockingOrder() {

        Long orderId = 10L;

        Order order = mock(Order.class);

        when(order.getStatus())
                .thenReturn(OrderStatus.ORDERED);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        PaymentCaptureStart expected =
                new PaymentCaptureStart(
                        20L,
                        30L,
                        "pf_test_123",
                        "capture-key-123");

        when(paymentService.startCapture(order))
                .thenReturn(expected);

        PaymentCaptureStart result =
                service.start(orderId);

        assertEquals(expected, result);

        verify(orderRepository)
                .findByIdForUpdate(orderId);

        verify(paymentService)
                .startCapture(order);
    }

    @Test
    void nonOrderedOrderDoesNotStartCapture() {

        Long orderId = 10L;

        Order order = mock(Order.class);

        when(order.getStatus())
                .thenReturn(OrderStatus.PAID);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        assertThrows(
                InvalidOrderStatusException.class,
                () -> service.start(orderId));

        verify(paymentService, never())
                .startCapture(order);
    }
}
