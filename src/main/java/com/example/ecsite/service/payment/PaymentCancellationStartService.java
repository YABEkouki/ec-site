package com.example.ecsite.service.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;

@Service
public class PaymentCancellationStartService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final Clock clock;

    @Autowired
    public PaymentCancellationStartService(
            OrderRepository orderRepository,
            PaymentService paymentService) {

        this(
                orderRepository,
                paymentService,
                Clock.system(ZoneId.of("Asia/Tokyo")));
    }

    PaymentCancellationStartService(
            OrderRepository orderRepository,
            PaymentService paymentService,
            Clock clock) {

        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.clock = clock;
    }

    @Transactional
    public PaymentCancellationStart start(
            Long orderId,
            Long userId) {

        Order order = orderRepository
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        LocalDateTime now = LocalDateTime.now(clock);

        if (!order.canCancel()) {
            throw new InvalidOrderStatusException(
                    "注文受付中の注文だけをキャンセルできます。");
        }

        if (!order.isWithinModificationPeriod(now)) {
            throw new InvalidOrderStatusException(
                    "この注文の変更受付は終了しています。");
        }

        return paymentService.startCancellation(order);
    }
}
