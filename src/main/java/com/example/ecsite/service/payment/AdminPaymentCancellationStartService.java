package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.PaymentTransactionInitiator;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.service.OrderService;

@Service
public class AdminPaymentCancellationStartService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final OrderPaymentGuard guard;
    private final OrderService orderService;

    public AdminPaymentCancellationStartService(
            OrderRepository orderRepository,
            PaymentService paymentService,
            OrderPaymentGuard guard,
            OrderService orderService) {

        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.guard = guard;
        this.orderService = orderService;
    }

    @Transactional
    public AdminCancellationStart start(
            Long orderId,
            Long accountId,
            String username,
            String internalNote) {

        Order order = orderRepository
                .findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!order.canCancel()) {
            throw new InvalidOrderStatusException(
                    "注文受付中の注文だけをキャンセルできます。");
        }

        if (!guard.requiresProviderCancellation(order)) {
            orderService.cancelOrder(orderId, accountId, username, internalNote);
            return AdminCancellationStart.local();
        }

        return AdminCancellationStart.provider(paymentService.startCancellation(
                order,
                new PaymentTransactionInitiator(
                        PaymentTransactionInitiatorType.ADMIN,
                        accountId,
                        username,
                        internalNote)));
    }
}
