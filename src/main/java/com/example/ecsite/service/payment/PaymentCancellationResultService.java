package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderService;

@Service
public class PaymentCancellationResultService {

    private final PaymentService paymentService;
    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public PaymentCancellationResultService(
            OrderRepository orderRepository,
            PaymentService paymentService,
            OrderService orderService) {

        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.orderService = orderService;
    }

    @Transactional
    public void apply(
            Long orderId,
            Long paymentId,
            Long userId,
            String username,
            CancellationResult result) {

        orderRepository
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        paymentService.validatePaymentBelongsToOrder(
                paymentId,
                orderId);

        boolean stateChanged = paymentService.applyCancellationResult(
                paymentId,
                result);

        if (!stateChanged
                || result.status() != CancellationResultStatus.CANCELLED) {
            return;
        }

        orderService.cancelOrderForUserAfterPaymentCancellation(
                orderId,
                userId,
                username);
    }
}
