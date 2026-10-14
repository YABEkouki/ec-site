package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;

@Service
public class PaymentAuthorizationStartService {
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;

    public PaymentAuthorizationStartService(OrderRepository orderRepository, PaymentService paymentService) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
    }

    @Transactional
    public PaymentAuthorizationStart start(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.ORDERED || order.getTotalAmount() <= 0) {
            throw new IllegalStateException("現在の注文状態ではカード与信を開始できません。");
        }
        return paymentService.startAuthorization(order);
    }
}
