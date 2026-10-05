package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.payment.CaptureResult;
import com.example.ecsite.payment.CaptureResultStatus;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderService;

@Service
public class PaymentCaptureResultService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final OrderService orderService;

    public PaymentCaptureResultService(
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
            Long accountId,
            String username,
            String internalNote,
            CaptureResult result) {

        Order order = orderRepository
                .findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        paymentService.validatePaymentBelongsToOrder(
                paymentId,
                order.getId());

        boolean stateChanged = paymentService.applyCaptureResult(
                paymentId,
                result);

        if (!stateChanged
                || result.status() != CaptureResultStatus.CAPTURED) {
            return;
        }

        orderService.markAsShippedAfterPaymentCapture(
                order,
                accountId,
                username,
                internalNote);
    }
}
