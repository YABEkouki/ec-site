package com.example.ecsite.service.payment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Order;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderService;

@ExtendWith(MockitoExtension.class)
class PaymentCancellationResultServiceTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private OrderService orderService;

    @Mock
    private OrderRepository orderRepository;

    private PaymentCancellationResultService service;

    @BeforeEach
    void setUp() {

        service = new PaymentCancellationResultService(
                orderRepository,
                paymentService,
                orderService);
    }

    @Test
    void cancelledResultUpdatesPaymentAndCancelsOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;
        Long userId = 30L;
        String username = "testuser";

        mockLockedOrder(
                orderId,
                userId);

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.CANCELLED,
                "pf_test_123");

        when(paymentService.applyCancellationResult(
                20L,
                result))
                .thenReturn(true);

        service.apply(
                orderId,
                paymentId,
                userId,
                username,
                result);

        InOrder inOrder = inOrder(
                orderRepository,
                paymentService,
                orderService);

        inOrder.verify(orderRepository)
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId);

        inOrder.verify(paymentService)
                .validatePaymentBelongsToOrder(
                        paymentId,
                        orderId);

        inOrder.verify(paymentService)
                .applyCancellationResult(
                        paymentId,
                        result);

        inOrder.verify(orderService)
                .cancelOrderForUserAfterPaymentCancellation(
                        orderId,
                        userId,
                        username);
    }

    @Test
    void pendingResultUpdatesPaymentButDoesNotCancelOrder() {

        Long orderId = 10L;
        Long paymentId = 20L;
        Long userId = 30L;
        String username = "testuser";

        mockLockedOrder(
                orderId,
                userId);

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.PENDING,
                "pf_test_123");

        service.apply(
                orderId,
                paymentId,
                userId,
                username,
                result);

        verify(orderRepository)
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId);

        verify(paymentService)
                .validatePaymentBelongsToOrder(
                        paymentId,
                        orderId);

        verify(paymentService)
                .applyCancellationResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .cancelOrderForUserAfterPaymentCancellation(
                        orderId,
                        userId,
                        username);
    }

    @Test
    void mismatchedPaymentDoesNotUpdateAnything() {

        Long orderId = 10L;
        Long paymentId = 20L;
        Long userId = 30L;
        String username = "testuser";

        mockLockedOrder(
                orderId,
                userId);

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.CANCELLED,
                "pf_test_123");

        doThrow(new IllegalArgumentException(
                "決済情報と注文が一致しません。"))
                .when(paymentService)
                .validatePaymentBelongsToOrder(
                        paymentId,
                        orderId);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.apply(
                        orderId,
                        paymentId,
                        userId,
                        username,
                        result));

        verify(orderRepository)
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId);

        verify(paymentService, never())
                .applyCancellationResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .cancelOrderForUserAfterPaymentCancellation(
                        orderId,
                        userId,
                        username);
    }

    @Test
    void alreadyAppliedCancellationDoesNotCancelOrderAgain() {

        Long orderId = 10L;
        Long paymentId = 20L;
        Long userId = 30L;
        String username = "testuser";

        mockLockedOrder(
                orderId,
                userId);

        CancellationResult result = new CancellationResult(
                CancellationResultStatus.CANCELLED,
                "pf_test_123");

        when(paymentService.applyCancellationResult(
                paymentId,
                result))
                .thenReturn(false);

        service.apply(
                orderId,
                paymentId,
                userId,
                username,
                result);

        verify(orderRepository)
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId);

        verify(paymentService)
                .validatePaymentBelongsToOrder(
                        paymentId,
                        orderId);

        verify(paymentService)
                .applyCancellationResult(
                        paymentId,
                        result);

        verify(orderService, never())
                .cancelOrderForUserAfterPaymentCancellation(
                        orderId,
                        userId,
                        username);
    }

    private void mockLockedOrder(
            Long orderId,
            Long userId) {

        Order order = org.mockito.Mockito.mock(Order.class);

        when(orderRepository.findByIdAndUserIdForUpdate(
                orderId,
                userId))
                .thenReturn(Optional.of(order));
    }
}
