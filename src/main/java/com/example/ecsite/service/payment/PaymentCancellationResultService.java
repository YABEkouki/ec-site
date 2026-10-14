package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderService;

@Service
public class PaymentCancellationResultService {
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final OrderService orderService;

    public PaymentCancellationResultService(OrderRepository orderRepository, PaymentService paymentService, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.orderService = orderService;
    }

    @Transactional
    public PaymentResultApplication apply(PaymentOperationSnapshot snapshot, CancellationResult result) {
        Order order = orderRepository.findByIdForUpdate(snapshot.orderId())
                .orElseThrow(() -> new OrderNotFoundException(snapshot.orderId()));
        if (snapshot.initiatorType() != PaymentTransactionInitiatorType.USER
                || !java.util.Objects.equals(order.getUserId(), snapshot.initiatorId())) return PaymentResultApplication.CONFLICT;
        PaymentResultApplication outcome = paymentService.applyCancellationResult(order, snapshot, result);
        if (outcome == PaymentResultApplication.APPLIED && result.status() == CancellationResultStatus.CANCELLED)
            orderService.cancelOrderForUserAfterPaymentCancellation(snapshot.orderId(), snapshot.initiatorId(), snapshot.initiatorUsername());
        return outcome;
    }
}
