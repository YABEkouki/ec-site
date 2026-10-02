package com.example.ecsite.service.payment;

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
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderService;

class AdminPaymentCancellationResultServiceTest {

    private OrderRepository orderRepository;
    private PaymentService paymentService;
    private OrderService orderService;
    private AdminPaymentCancellationResultService service;

    @BeforeEach
    void setUp() {

        orderRepository = mock(OrderRepository.class);
        paymentService = mock(PaymentService.class);
        orderService = mock(OrderService.class);

        service = new AdminPaymentCancellationResultService(
                orderRepository,
                paymentService,
                orderService);
    }

    @Test
    void cancelledResultUpdatesPaymentThenCancelsOrder() {

        Long orderId = 1L;
        Long paymentId = 10L;
        Long accountId = 20L;

        Order order = mock(Order.class);

        when(order.getId())
                .thenReturn(orderId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.CANCELLED,
                "provider-transaction-id");

        service.apply(
                orderId,
                paymentId,
                accountId,
                "admin",
                "管理者キャンセル",
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
                .applyCancellationResult(
                        paymentId,
                        result);

        inOrder.verify(orderService)
                .cancelOrderAfterPaymentCancellation(
                        order,
                        accountId,
                        "admin",
                        "管理者キャンセル");
    }

    @Test
    void pendingResultUpdatesPaymentButDoesNotCancelOrder() {

        Long orderId = 1L;
        Long paymentId = 10L;

        Order order = mock(Order.class);

        when(order.getId())
                .thenReturn(orderId);

        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order));

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.PENDING,
                "provider-transaction-id");

        service.apply(
                orderId,
                paymentId,
                20L,
                "admin",
                "管理者キャンセル",
                result);

        verify(paymentService)
                .applyCancellationResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .cancelOrderAfterPaymentCancellation(
                        order,
                        20L,
                        "admin",
                        "管理者キャンセル");
    }
}
