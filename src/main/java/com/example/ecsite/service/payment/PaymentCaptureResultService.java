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
public class PaymentCaptureResultService {
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final OrderService orderService;

    public PaymentCaptureResultService(OrderRepository orderRepository, PaymentService paymentService, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.orderService = orderService;
    }

    @Transactional
    public PaymentResultApplication apply(PaymentOperationSnapshot snapshot, CaptureResult result) {
        Order order = orderRepository.findByIdForUpdate(snapshot.orderId())
                .orElseThrow(() -> new OrderNotFoundException(snapshot.orderId()));
        if (snapshot.initiatorType() != PaymentTransactionInitiatorType.ADMIN) return PaymentResultApplication.CONFLICT;
        PaymentResultApplication outcome = paymentService.applyCaptureResult(order, snapshot, result);
        if (outcome == PaymentResultApplication.APPLIED && result.status() == CaptureResultStatus.CAPTURED)
            orderService.markAsShippedAfterPaymentCapture(order, snapshot.initiatorId(), snapshot.initiatorUsername(), snapshot.internalNote());
        return outcome;
    }
}
