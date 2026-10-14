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
public class PaymentAuthorizationResultService {
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final OrderService orderService;

    public PaymentAuthorizationResultService(OrderRepository orderRepository, PaymentService paymentService, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.orderService = orderService;
    }

    @Transactional
    public PaymentResultApplication apply(PaymentOperationSnapshot snapshot, AuthorizationResult result) {
        Order order = orderRepository.findByIdForUpdate(snapshot.orderId())
                .orElseThrow(() -> new OrderNotFoundException(snapshot.orderId()));
        PaymentResultApplication outcome = paymentService.applyAuthorizationResult(order, snapshot, result);
        if (outcome == PaymentResultApplication.APPLIED && result.status() == AuthorizationResultStatus.FAILED)
            orderService.cancelOrderForPaymentFailure(snapshot.orderId());
        return outcome;
    }

    @Transactional
    public PaymentResultApplication setProviderPaymentId(PaymentOperationSnapshot snapshot, String providerPaymentId) {
        Order order = orderRepository.findByIdForUpdate(snapshot.orderId())
                .orElseThrow(() -> new OrderNotFoundException(snapshot.orderId()));
        return paymentService.setProviderPaymentId(order, snapshot, providerPaymentId);
    }
}
