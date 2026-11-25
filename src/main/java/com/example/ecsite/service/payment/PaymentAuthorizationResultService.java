package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.PaymentTransactionInitiatorType;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.payment.*;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.entity.PaymentTransactionPurpose;
import com.example.ecsite.service.OrderAcceptanceService;
import com.example.ecsite.service.OrderService;

@Service
public class PaymentAuthorizationResultService {
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final OrderService orderService;
    private final OrderAcceptanceService acceptance;
    private com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore closures;

    /** Feature142-A: Spring always injects it; hand-built unit fixtures predate V110, which still guards the database. */
    @org.springframework.beans.factory.annotation.Autowired
    void setUnpaidOrderClosures(com.example.ecsite.service.unpaidclosure.UnpaidOrderClosureStore closures) {
        this.closures = closures;
    }

    public PaymentAuthorizationResultService(OrderRepository orderRepository, PaymentService paymentService, OrderService orderService,
            OrderAcceptanceService acceptance) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.orderService = orderService;
        this.acceptance = acceptance;
    }

    @Transactional
    public PaymentResultApplication apply(PaymentOperationSnapshot snapshot, AuthorizationResult result) {
        Order order = orderRepository.findByIdForUpdate(snapshot.orderId())
                .orElseThrow(() -> new OrderNotFoundException(snapshot.orderId()));
        boolean closing = closures != null && closures.isOpen(snapshot.orderId());
        // Its cancel may still reach the Payment Flow; decided under the Order lock a closure is requested with.
        if (closing && result.status() == AuthorizationResultStatus.AUTHORIZED) return PaymentResultApplication.DEFERRED;
        PaymentResultApplication outcome = paymentService.applyAuthorizationResult(order, snapshot, result);
        if (outcome == PaymentResultApplication.APPLIED && result.status() == AuthorizationResultStatus.FAILED) {
            if (closing) {
                orderService.cancelUnpaidOrder(snapshot.orderId());
                closures.markClosed(snapshot.orderId());
            } else {
                orderService.cancelOrderForPaymentFailure(snapshot.orderId());
            }
        }
        // Feature127: the first successful initial authorization formally accepts the order in
        // this same transaction. Customer return, webhook and reconciliation all converge here.
        if (outcome == PaymentResultApplication.APPLIED && result.status() == AuthorizationResultStatus.AUTHORIZED
                && snapshot.purpose() == PaymentTransactionPurpose.INITIAL_ORDER_AUTHORIZATION)
            acceptance.acceptAfterInitialAuthorization(order, snapshot.paymentId(), snapshot.transactionId());
        return outcome;
    }

    @Transactional
    public PaymentResultApplication setProviderPaymentId(PaymentOperationSnapshot snapshot, String providerPaymentId) {
        Order order = orderRepository.findByIdForUpdate(snapshot.orderId())
                .orElseThrow(() -> new OrderNotFoundException(snapshot.orderId()));
        return paymentService.setProviderPaymentId(order, snapshot, providerPaymentId);
    }
}
