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
public class AdminPaymentCancellationResultService {
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final OrderService orderService;

    public AdminPaymentCancellationResultService(OrderRepository orderRepository, PaymentService paymentService, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.orderService = orderService;
    }

    @Transactional
    public PaymentResultApplication apply(PaymentOperationSnapshot snapshot, CancellationResult result) {
        Order order = orderRepository.findByIdForUpdate(snapshot.orderId())
                .orElseThrow(() -> new OrderNotFoundException(snapshot.orderId()));
        if (snapshot.initiatorType() != PaymentTransactionInitiatorType.ADMIN) return PaymentResultApplication.CONFLICT;
        PaymentResultApplication outcome = paymentService.applyCancellationResult(order, snapshot, result);
        if (outcome == PaymentResultApplication.APPLIED && result.status() == CancellationResultStatus.CANCELLED) {
            // Feature146-B: the cancellation of a shipping stop completes the stop before the prepared order is cancelled.
            var stopReason = shippingStops == null ? java.util.Optional.<String>empty() : shippingStops.requestedReason(snapshot.transactionId());
            if (stopReason.isPresent()) {
                shippingStops.complete(snapshot.transactionId());
                orderService.cancelOrderForShippingStop(order, snapshot.initiatorId(), snapshot.initiatorUsername(), "出荷中止：" + stopReason.get());
            } else {
                orderService.cancelOrderAfterPaymentCancellation(order, snapshot.initiatorId(), snapshot.initiatorUsername(), snapshot.internalNote());
            }
        }
        return outcome;
    }

    private com.example.ecsite.service.shippingstop.ShippingStopRecords shippingStops;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setShippingStops(com.example.ecsite.service.shippingstop.ShippingStopRecords shippingStops) {
        this.shippingStops = shippingStops;
    }
}
