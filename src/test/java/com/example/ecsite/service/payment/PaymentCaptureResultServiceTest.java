package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderService;

class PaymentCaptureResultServiceTest {

    private OrderRepository orderRepository;
    private PaymentService paymentService;
    private OrderService orderService;
    private PaymentCaptureResultService service;

    @BeforeEach
    void setUp() {

        orderRepository = mock(OrderRepository.class);
        paymentService = mock(PaymentService.class);
        orderService = mock(OrderService.class);

        service = new PaymentCaptureResultService(
                orderRepository,
                paymentService,
                orderService);
    }

    @Test
    void capturedResultUpdatesPaymentThenShipsOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;

        Order order = mock(Order.class);

        when(order.getId())
                .thenReturn(orderId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        CaptureResult result = new CaptureResult(
                CaptureResultStatus.CAPTURED,
                "pf_test_123",
                null,
                null);

        when(paymentService.applyCaptureResult(
                20L,
                result))
                .thenReturn(true);

        service.apply(
                orderId,
                paymentId,
                30L,
                "admin",
                "発送処理",
                result);

        InOrder inOrder = inOrder(
                orderRepository,
                paymentService,
                orderService);

        inOrder.verify(orderRepository)
                .findByIdForUpdate(orderId);

        inOrder.verify(paymentService)
                .validatePaymentBelongsToOrder(
                        paymentId,
                        orderId);

        inOrder.verify(paymentService)
                .applyCaptureResult(
                        paymentId,
                        result);

        inOrder.verify(orderService)
                .markAsShippedAfterPaymentCapture(
                        order,
                        30L,
                        "admin",
                        "発送処理");
    }

    @Test
    void pendingResultUpdatesPaymentButDoesNotShipOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;

        Order order = mock(Order.class);

        when(order.getId())
                .thenReturn(orderId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        CaptureResult result = new CaptureResult(
                CaptureResultStatus.PENDING,
                "pf_test_123",
                null,
                null);

        service.apply(
                orderId,
                paymentId,
                30L,
                "admin",
                "発送処理",
                result);

        verify(paymentService)
                .validatePaymentBelongsToOrder(
                        paymentId,
                        orderId);

        verify(paymentService)
                .applyCaptureResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .markAsShippedAfterPaymentCapture(
                        order,
                        30L,
                        "admin",
                        "発送処理");
    }

    @Test
    void failedResultUpdatesPaymentButDoesNotShipOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;

        Order order = mock(Order.class);

        when(order.getId())
                .thenReturn(orderId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        CaptureResult result = new CaptureResult(
                CaptureResultStatus.FAILED,
                "pf_test_123",
                "capture_failed",
                "Capture failed");

        service.apply(
                orderId,
                paymentId,
                30L,
                "admin",
                "発送処理",
                result);

        verify(paymentService)
                .applyCaptureResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .markAsShippedAfterPaymentCapture(
                        order,
                        30L,
                        "admin",
                        "発送処理");
    }

    @Test
    void paymentOrderMismatchDoesNotApplyResultOrShipOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;

        Order order = mock(Order.class);

        when(order.getId())
                .thenReturn(orderId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        CaptureResult result = new CaptureResult(
                CaptureResultStatus.CAPTURED,
                "pf_test_123",
                null,
                null);

        org.mockito.Mockito.doThrow(
                new IllegalArgumentException(
                        "決済情報が注文と一致しません。"))
                .when(paymentService)
                .validatePaymentBelongsToOrder(
                        paymentId,
                        orderId);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.apply(
                        orderId,
                        paymentId,
                        30L,
                        "admin",
                        "発送処理",
                        result));

        verify(paymentService, never())
                .applyCaptureResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .markAsShippedAfterPaymentCapture(
                        order,
                        30L,
                        "admin",
                        "発送処理");
    }

    @Test
    void alreadyAppliedCaptureDoesNotShipOrderAgain() {

        Order order = mock(Order.class);

        when(orderRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(order));

        CaptureResult result = new CaptureResult(
                CaptureResultStatus.CAPTURED,
                "pf_test_123",
                null,
                null);

        when(paymentService.applyCaptureResult(
                20L,
                result))
                .thenReturn(false);

        service.apply(
                10L,
                20L,
                30L,
                "admin",
                "発送処理",
                result);

        verify(paymentService)
                .applyCaptureResult(
                        20L,
                        result);

        verify(orderService, never())
                .markAsShippedAfterPaymentCapture(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

}
