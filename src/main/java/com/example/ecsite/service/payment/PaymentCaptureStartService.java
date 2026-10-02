package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;

@Service
public class PaymentCaptureStartService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;

    public PaymentCaptureStartService(
            OrderRepository orderRepository,
            PaymentService paymentService) {

        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
    }

    @Transactional
    public PaymentCaptureStart start(Long orderId) {

        Order order = orderRepository
                .findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.ORDERED) {
            throw new InvalidOrderStatusException(
                    "注文受付中の注文だけを売上確定できます。");
        }

        return paymentService.startCapture(order);
    }
}
