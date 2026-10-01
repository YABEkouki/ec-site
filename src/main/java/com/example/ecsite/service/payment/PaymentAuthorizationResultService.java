package com.example.ecsite.service.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.service.OrderService;

@Service
public class PaymentAuthorizationResultService {

    private final PaymentService paymentService;
    private final OrderService orderService;

    public PaymentAuthorizationResultService(
            PaymentService paymentService,
            OrderService orderService) {

        this.paymentService = paymentService;
        this.orderService = orderService;
    }

    @Transactional
    public void apply(
            Long orderId,
            Long paymentId,
            AuthorizationResult result) {

        paymentService.applyAuthorizationResult(
                paymentId,
                result);

        if (result.status()
                == AuthorizationResultStatus.FAILED) {

            orderService.cancelOrderForPaymentFailure(
                    orderId);
        }
    }
}
