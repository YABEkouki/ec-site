package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.service.OrderService;

@Service
public class PaymentCancellationResultService {

    private final PaymentService paymentService;
    private final OrderService orderService;

    public PaymentCancellationResultService(
            PaymentService paymentService,
            OrderService orderService) {

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

        paymentService.validatePaymentBelongsToOrder(
                paymentId,
                orderId);

        paymentService.applyCancellationResult(
                paymentId,
                result);

        if (result.status() != CancellationResultStatus.CANCELLED) {
            return;
        }

        orderService.cancelOrderForUserAfterPaymentCancellation(
                orderId,
                userId,
                username);
    }
}
