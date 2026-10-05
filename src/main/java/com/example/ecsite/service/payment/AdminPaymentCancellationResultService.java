package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.payment.CancellationResult;
import com.example.ecsite.payment.CancellationResultStatus;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderService;

@Service
public class AdminPaymentCancellationResultService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final OrderService orderService;

    public AdminPaymentCancellationResultService(
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
            CancellationResult result) {

        Order order = orderRepository
                .findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        paymentService.validatePaymentBelongsToOrder(
                paymentId,
                order.getId());

        boolean stateChanged = paymentService.applyCancellationResult(
                paymentId,
                result);

        if (!stateChanged
                || result.status() != CancellationResultStatus.CANCELLED) {
            return;
        }

        orderService.cancelOrderAfterPaymentCancellation(
                order,
                accountId,
                username,
                internalNote);
    }
}
