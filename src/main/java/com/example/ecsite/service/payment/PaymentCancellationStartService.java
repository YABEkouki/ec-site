package com.example.ecsite.service.payment;


import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.PaymentTransactionInitiator;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;

@Service
public class PaymentCancellationStartService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;

    public PaymentCancellationStartService(
            OrderRepository orderRepository,
            PaymentService paymentService) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
    }

    @Transactional
    public PaymentCancellationStart start(
            Long orderId,
            Long userId,
            String username) {

        Order order = orderRepository
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!order.canCancel()) {
            throw new InvalidOrderStatusException(
                    "注文受付中の注文だけをキャンセルできます。");
        }

        // PaymentService checks the window after Payment locks, only for NEW
        // cancellations. Accepted PENDING operations may continue after the deadline.
        return paymentService.startCancellation(
                order,
                new PaymentTransactionInitiator(
                        PaymentTransactionInitiatorType.USER,
                        userId,
                        username,
                        null));
    }
}
